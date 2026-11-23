package com.hyperdrive.backend.storage.provider.dropbox;

import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.provider.AccountRoot;
import com.hyperdrive.backend.storage.provider.FileBrowser;
import com.hyperdrive.backend.storage.provider.FilePage;
import com.hyperdrive.backend.storage.provider.ProviderAccessToken;
import com.hyperdrive.backend.storage.provider.ProviderException;
import com.hyperdrive.backend.storage.provider.ProviderException.Category;
import com.hyperdrive.backend.storage.provider.ProviderFile;
import com.hyperdrive.backend.storage.provider.ProviderFile.FileKind;
import com.hyperdrive.backend.storage.provider.ProviderHttp;
import com.hyperdrive.backend.storage.provider.ProviderHttp.Response;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dropbox API v2 adapter (HTTP, read-only metadata). Uses only documented endpoints:
 * {@code users/get_current_account}, {@code files/list_folder}, {@code files/list_folder/continue}
 * and {@code files/get_metadata}.
 *
 * <ul>
 *   <li>Every files call carries {@code Dropbox-API-Path-Root: {".tag":"root","root":<root namespace>}},
 *       which Dropbox documents as working for both personal accounts and team-space members, so
 *       path {@code ""} is the account's real root (the team space for team-space members).</li>
 *   <li>Folders are listed by {@code path_lower} (the documented path form); files are identified by
 *       their stable {@code id:...}. Neither is ever combined into or derived from the other.</li>
 *   <li>Initial listing ({@code list_folder}) and continuation ({@code list_folder/continue}) are
 *       separate calls; a {@code reset} cursor error surfaces as {@link Category#CURSOR_RESET}.</li>
 *   <li>Nothing is invented: Dropbox has no MIME types, so {@code mimeType} is null; deleted entries
 *       are skipped; links (symlinks) are shown as shortcuts and never followed.</li>
 * </ul>
 */
public class DropboxBrowser implements FileBrowser, ProviderHttp.Classifier {

    public static final String CONTENT_HASH_ALGORITHM = "dropbox-content-hash";
    private static final Pattern ACCOUNT_ID = Pattern.compile("^dbid:[A-Za-z0-9_-]{1,200}$");
    private static final Pattern NAMESPACE_ID = Pattern.compile("^[0-9]{1,64}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(DropboxBrowser.class);

    /** The validated Dropbox account behind a token (from users/get_current_account). */
    public record DropboxAccount(String accountId, String displayName, String verifiedEmail, String rootType,
            String rootNamespaceId) {
        @Override
        public String toString() {
            return "DropboxAccount[rootType=" + rootType + "]";
        }
    }

    private final RestClient http;
    private final URI apiBaseUrl;
    private final ProviderHttp transport;

    public DropboxBrowser(RestClient http, StorageProperties properties, ProviderHttp.Sleeper sleeper) {
        this.http = http;
        this.apiBaseUrl = properties.dropbox().apiBaseUrl();
        this.transport = new ProviderHttp(properties, sleeper, "Dropbox");
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.DROPBOX;
    }

    /** users/get_current_account (scope account_info.read). No path root: it is not a files call. */
    public DropboxAccount currentAccount(ProviderAccessToken token) {
        JsonNode body = post(token, "users/get_current_account", null, null);
        String accountId = text(body, "account_id");
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Dropbox returned no usable account id");
        }
        if (body.path("disabled").asBoolean(false)) {
            throw new ProviderException(Category.PERMISSION_DENIED, "Dropbox account is disabled");
        }
        JsonNode rootInfo = body.path("root_info");
        String tag = text(rootInfo, ".tag");
        String rootNamespace = text(rootInfo, "root_namespace_id");
        if (!("user".equals(tag) || "team".equals(tag)) || rootNamespace == null
                || !NAMESPACE_ID.matcher(rootNamespace).matches()) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Dropbox account root is not supported");
        }
        String email = body.path("email_verified").asBoolean(false) ? text(body, "email") : null;
        return new DropboxAccount(accountId, truncate(text(body.path("name"), "display_name"), 200),
                truncate(email, 320), "team".equals(tag) ? "TEAM" : "USER", rootNamespace);
    }

    @Override
    public FilePage listChildren(ProviderAccessToken token, AccountRoot root, String directory, String providerCursor,
            int pageSize) {
        JsonNode body;
        if (providerCursor == null) {
            if (directory != null && !StorageProvider.DROPBOX.isValidDirectory(directory)) {
                throw new IllegalArgumentException("Invalid Dropbox directory");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", directory == null ? "" : directory);
            args.put("recursive", false);
            args.put("include_deleted", false);
            args.put("include_mounted_folders", true);
            args.put("include_non_downloadable_files", true);
            args.put("limit", pageSize); // Dropbox documents limit as approximate
            body = post(token, "files/list_folder", args, root);
        } else {
            body = post(token, "files/list_folder/continue", Map.of("cursor", providerCursor), root);
        }
        JsonNode entries = body.get("entries");
        if (entries == null || !entries.isArray()) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Dropbox listing had an unexpected shape");
        }
        List<ProviderFile> items = new ArrayList<>();
        int skipped = 0;
        for (JsonNode entry : entries) {
            String tag = text(entry, ".tag");
            if ("deleted".equals(tag)) {
                continue;
            }
            ProviderFile file = toProviderFile(entry);
            if (file == null) {
                skipped++;
            } else {
                items.add(file);
            }
        }
        if (skipped > 0) {
            log.warn("Skipped {} Dropbox entries without a usable id or of an unknown type", skipped);
        }
        String cursor = text(body, "cursor");
        boolean hasMore = body.path("has_more").asBoolean(false);
        if (hasMore && (cursor == null || cursor.isEmpty())) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Dropbox reported more entries without a cursor");
        }
        return new FilePage(items, hasMore ? cursor : null);
    }

    @Override
    public ProviderFile getFile(ProviderAccessToken token, AccountRoot root, String fileId) {
        if (!StorageProvider.DROPBOX.isValidFileId(fileId)) {
            throw new IllegalArgumentException("Invalid Dropbox file id");
        }
        JsonNode body = post(token, "files/get_metadata", Map.of("path", fileId, "include_deleted", false), root);
        if ("deleted".equals(text(body, ".tag"))) {
            throw new ProviderException(Category.NOT_FOUND, "Dropbox item was deleted");
        }
        ProviderFile file = toProviderFile(body);
        if (file == null) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Dropbox returned metadata without a usable id");
        }
        return file;
    }

    /** Maps a files.Metadata entry; null for unknown types or unusable ids. */
    static ProviderFile toProviderFile(JsonNode entry) {
        String tag = text(entry, ".tag");
        String id = text(entry, "id");
        if (!StorageProvider.DROPBOX.isValidFileId(id)) {
            return null;
        }
        String name = text(entry, "name");
        if ("folder".equals(tag)) {
            String path = text(entry, "path_lower");
            return new ProviderFile(id, name == null ? "" : name, FileKind.FOLDER, null, null, null, false,
                    StorageProvider.DROPBOX.isValidDirectory(path) ? path : null, null, null, null, null, null);
        }
        if (!"file".equals(tag)) {
            return null;
        }
        Instant modified = parseInstant(text(entry, "server_modified"));
        String rev = text(entry, "rev");
        JsonNode symlink = entry.get("symlink_info");
        if (symlink != null && symlink.isObject()) {
            // A link: shown distinctly, never followed.
            return new ProviderFile(id, name == null ? "" : name, FileKind.SHORTCUT, null, null, modified, false,
                    null, rev, null, null, null, null);
        }
        if (!entry.path("is_downloadable").asBoolean(true)) {
            // Non-downloadable cloud documents (they need export): no download size.
            return new ProviderFile(id, name == null ? "" : name, FileKind.PROVIDER_DOCUMENT, null, null, modified,
                    false, null, rev, null, null, null, null);
        }
        JsonNode size = entry.get("size");
        Long sizeBytes = size != null && size.isIntegralNumber() && size.asLong() >= 0 ? size.asLong() : null;
        String hash = text(entry, "content_hash");
        return new ProviderFile(id, name == null ? "" : name, FileKind.FILE, null, sizeBytes, modified, true, null,
                rev, hash, hash == null ? null : CONTENT_HASH_ALGORITHM, null, null);
    }

    private JsonNode post(ProviderAccessToken token, String endpoint, Object args, AccountRoot root) {
        URI uri = UriComponentsBuilder.fromUri(apiBaseUrl).path("/" + endpoint).build().toUri();
        String pathRoot = root == null ? null : pathRootHeader(root.rootNamespaceId());
        byte[] payload = args == null ? null : JSON.writeValueAsBytes(args);
        Response response = transport.execute(() -> {
            RestClient.RequestBodySpec request = http.post().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                    .accept(MediaType.APPLICATION_JSON);
            if (pathRoot != null) {
                request.header("Dropbox-API-Path-Root", pathRoot);
            }
            if (payload != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(payload);
            }
            return request.exchange((req, res) -> new Response(res.getStatusCode().value(),
                    res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), res.getBody().readAllBytes()), true);
        }, this);
        return parse(response.body());
    }

    /** Result of one {@code auth/token/revoke} attempt. */
    public enum RevokeOutcome {
        /** Dropbox answered 2xx: the token, and per its docs the refresh token it came from, is disabled. */
        CONFIRMED,
        /** Dropbox answered with a definite error (e.g. 400/401/403/409): nothing was confirmed revoked. */
        FAILED,
        /** No answer, a timeout, 429 or 5xx: the request may or may not have been processed. */
        UNKNOWN
    }

    /**
     * {@code POST /2/auth/token/revoke} with the given token and no body. Dropbox documents: "Disables
     * the access token used to authenticate the call. If there is a corresponding refresh token for the
     * access token, this disables that refresh token, as well as any other access tokens for that
     * refresh token." One attempt, no retries; the response body is discarded, never logged.
     */
    public RevokeOutcome revokeToken(ProviderAccessToken token) {
        URI uri = UriComponentsBuilder.fromUri(apiBaseUrl).path("/auth/token/revoke").build().toUri();
        try {
            int status = http.post().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                    .exchange((req, res) -> {
                        res.getBody().readAllBytes();
                        return res.getStatusCode().value();
                    }, true);
            if (status >= 200 && status < 300) {
                return RevokeOutcome.CONFIRMED;
            }
            return status == 429 || status >= 500 ? RevokeOutcome.UNKNOWN : RevokeOutcome.FAILED;
        } catch (RestClientException e) {
            return RevokeOutcome.UNKNOWN;
        }
    }

    static String pathRootHeader(String rootNamespaceId) {
        if (rootNamespaceId == null || !NAMESPACE_ID.matcher(rootNamespaceId).matches()) {
            throw new IllegalArgumentException("Invalid Dropbox root namespace");
        }
        // Deterministic key order, matching Dropbox's documented form {".tag": "root", "root": "<id>"}.
        Map<String, String> header = new LinkedHashMap<>();
        header.put(".tag", "root");
        header.put("root", rootNamespaceId);
        return JSON.writeValueAsString(header);
    }

    @Override
    public ProviderException classify(Response response) {
        return classifyResponse(response);
    }

    /** Dropbox status semantics: 401 auth/scope, 403 access, 409 endpoint error, 422 path root, 429 rate. */
    static ProviderException classifyResponse(Response response) {
        int status = response.status();
        JsonNode body = tryParse(response.body());
        JsonNode error = body == null ? null : body.get("error");
        String tag = text(error, ".tag");
        if (status == 401) {
            return "missing_scope".equals(tag)
                    ? new ProviderException(Category.INSUFFICIENT_SCOPE, "Dropbox grant lacks a required scope")
                    : new ProviderException(Category.AUTHENTICATION, "Dropbox rejected the access token");
        }
        if (status == 429) {
            Duration retryAfter = ProviderHttp.parseRetryAfter(response.retryAfter());
            if (retryAfter == null && error != null && error.path("retry_after").isIntegralNumber()) {
                retryAfter = Duration.ofSeconds(Math.max(0, error.path("retry_after").asLong()));
            }
            return new ProviderException(Category.RATE_LIMITED, "Dropbox rate limit reached", retryAfter);
        }
        if (status == 403) {
            return new ProviderException(Category.PERMISSION_DENIED, "Dropbox denied access to this feature or item");
        }
        if (status == 409) {
            if ("reset".equals(tag)) {
                return new ProviderException(Category.CURSOR_RESET, "Dropbox cursor was reset");
            }
            if ("path".equals(tag)) {
                String lookup = text(error.path("path"), ".tag");
                return "not_found".equals(lookup)
                        ? new ProviderException(Category.NOT_FOUND, "Dropbox item not found")
                        : new ProviderException(Category.PERMISSION_DENIED, "Dropbox can't list this item: " + safeTag(lookup));
            }
            return new ProviderException(Category.INVALID_RESPONSE, "Dropbox endpoint error: " + safeTag(tag));
        }
        if (status == 422) {
            return new ProviderException(Category.ROOT_UNAVAILABLE, "Dropbox rejected the account root namespace");
        }
        if (status == 400) {
            return new ProviderException(Category.INVALID_RESPONSE, "Dropbox rejected the request");
        }
        if (status >= 500) {
            return new ProviderException(Category.UNAVAILABLE, "Dropbox returned a server error",
                    ProviderHttp.parseRetryAfter(response.retryAfter()));
        }
        return new ProviderException(Category.INVALID_RESPONSE, "Dropbox returned an unexpected status " + status);
    }

    @Override
    public boolean retryable(Response response, ProviderException failure) {
        return failure.category() == Category.UNAVAILABLE || failure.category() == Category.RATE_LIMITED;
    }

    /** Error tags are Dropbox-defined identifiers; anything else is not echoed into logs. */
    private static String safeTag(String tag) {
        return tag != null && tag.matches("[a-z_]{1,64}") ? tag : "unknown";
    }

    private static JsonNode tryParse(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonNode parse(byte[] body) {
        JsonNode node = tryParse(body);
        if (node == null) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Dropbox response was not a JSON object");
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
