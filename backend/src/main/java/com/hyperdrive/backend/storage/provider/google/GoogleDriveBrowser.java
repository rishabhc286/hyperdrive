package com.hyperdrive.backend.storage.provider.google;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google Drive API v3 adapter: read-only metadata of "My Drive". Requests only the fields HyperDrive
 * uses, excludes trashed items, never enumerates recursively, and maps Drive's JSON straight into
 * {@link ProviderFile}. Drive wire models never leave this class. Transport policy (retries,
 * backoff, Retry-After) is the shared {@link ProviderHttp}.
 */
public class GoogleDriveBrowser implements FileBrowser, ProviderHttp.Classifier {

    public static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    public static final String SHORTCUT_MIME = "application/vnd.google-apps.shortcut";
    private static final String NATIVE_PREFIX = "application/vnd.google-apps.";

    static final String FILE_FIELDS = "id,name,mimeType,size,modifiedTime,trashed,capabilities/canDownload,"
            + "headRevisionId,md5Checksum,shortcutDetails/targetId,shortcutDetails/targetMimeType";
    static final String LIST_FIELDS = "nextPageToken,files(" + FILE_FIELDS + ")";

    private static final Set<String> RATE_LIMIT_REASONS =
            Set.of("rateLimitExceeded", "userRateLimitExceeded", "RATE_LIMIT_EXCEEDED");
    private static final Set<String> QUOTA_REASONS = Set.of("dailyLimitExceeded", "quotaExceeded");
    private static final Set<String> SCOPE_REASONS = Set.of("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(GoogleDriveBrowser.class);

    private final RestClient http;
    private final URI baseUrl;
    private final ProviderHttp transport;

    public GoogleDriveBrowser(RestClient http, StorageProperties properties, ProviderHttp.Sleeper sleeper) {
        this.http = http;
        this.baseUrl = properties.driveApiBaseUrl();
        this.transport = new ProviderHttp(properties, sleeper, "Google Drive");
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.GOOGLE_DRIVE;
    }

    /** Resolves the My Drive root id at connect time ("root" is Drive's documented file-id alias). */
    public String rootFolderId(ProviderAccessToken token) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl).pathSegment("files", "root")
                .queryParam("fields", "id").queryParam("supportsAllDrives", "true")
                .encode().build().toUri();
        String id = text(call(token, uri), "id");
        if (!StorageProvider.GOOGLE_DRIVE.isValidFileId(id)) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Drive returned no usable root folder id");
        }
        return id;
    }

    @Override
    public FilePage listChildren(ProviderAccessToken token, AccountRoot root, String directory, String providerCursor,
            int pageSize) {
        String folderId = directory == null ? root.rootFolderId() : directory;
        if (!StorageProvider.GOOGLE_DRIVE.isValidDirectory(folderId)) {
            throw new IllegalArgumentException("Invalid Drive folder id");
        }
        URI uri = UriComponentsBuilder.fromUri(baseUrl).pathSegment("files")
                .queryParam("q", "{q}")
                .queryParam("pageSize", pageSize)
                .queryParam("fields", "{fields}")
                .queryParam("orderBy", "folder,name")
                .queryParam("spaces", "drive")
                .queryParam("corpora", "user")
                .queryParam("supportsAllDrives", "true")
                .queryParam("includeItemsFromAllDrives", "false")
                .queryParamIfPresent("pageToken", Optional.ofNullable(providerCursor).map(t -> "{pageToken}"))
                .encode()
                .buildAndExpand(providerCursor == null
                        ? Map.of("q", childrenQuery(folderId), "fields", LIST_FIELDS)
                        : Map.of("q", childrenQuery(folderId), "fields", LIST_FIELDS, "pageToken", providerCursor))
                .toUri();
        JsonNode body = call(token, uri);
        JsonNode files = body.get("files");
        if (files != null && !files.isArray()) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Drive listing had an unexpected shape");
        }
        List<ProviderFile> items = new ArrayList<>();
        int skipped = 0;
        if (files != null) {
            for (JsonNode node : files) {
                // The query already excludes trashed items; this is a second line of defense.
                if (node.path("trashed").asBoolean(false)) {
                    continue;
                }
                ProviderFile file = toProviderFile(node);
                if (file == null) {
                    skipped++;
                } else {
                    items.add(file);
                }
            }
        }
        if (skipped > 0) {
            log.warn("Skipped {} Drive entries without a usable id", skipped);
        }
        String next = text(body, "nextPageToken");
        return new FilePage(items, next == null || next.isEmpty() ? null : next);
    }

    @Override
    public ProviderFile getFile(ProviderAccessToken token, AccountRoot root, String fileId) {
        if (!StorageProvider.GOOGLE_DRIVE.isValidFileId(fileId)) {
            throw new IllegalArgumentException("Invalid Drive file id");
        }
        URI uri = UriComponentsBuilder.fromUri(baseUrl).pathSegment("files", "{id}")
                .queryParam("fields", "{fields}")
                .queryParam("supportsAllDrives", "true")
                .encode().buildAndExpand(Map.of("id", fileId, "fields", FILE_FIELDS)).toUri();
        JsonNode node = call(token, uri);
        if (node.path("trashed").asBoolean(false)) {
            throw new ProviderException(Category.NOT_FOUND, "File is in the trash");
        }
        ProviderFile file = toProviderFile(node);
        if (file == null) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Drive returned a file without a usable id");
        }
        return file;
    }

    /**
     * Drive query for direct children. The id is validated to Drive's id alphabet first; escaping
     * backslashes and single quotes (Drive query string rules) is kept as defense in depth.
     */
    static String childrenQuery(String folderId) {
        return "'" + escapeQueryValue(folderId) + "' in parents and trashed = false";
    }

    static String escapeQueryValue(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** Maps one Drive file resource; null if it has no usable id. Never invents sizes. */
    static ProviderFile toProviderFile(JsonNode node) {
        String id = text(node, "id");
        if (!StorageProvider.GOOGLE_DRIVE.isValidFileId(id)) {
            return null;
        }
        String mimeType = text(node, "mimeType");
        FileKind kind;
        if (FOLDER_MIME.equals(mimeType)) {
            kind = FileKind.FOLDER;
        } else if (SHORTCUT_MIME.equals(mimeType)) {
            kind = FileKind.SHORTCUT;
        } else if (mimeType != null && mimeType.startsWith(NATIVE_PREFIX)) {
            kind = FileKind.PROVIDER_DOCUMENT;
        } else {
            kind = FileKind.FILE;
        }
        // Drive reports a storage size for some native documents too, but that is not the size of
        // anything downloadable, so only ordinary files carry a size.
        Long size = kind == FileKind.FILE ? parseSize(text(node, "size")) : null;
        boolean canDownload = kind == FileKind.FILE && node.path("capabilities").path("canDownload").asBoolean(false);
        JsonNode shortcut = node.path("shortcutDetails");
        String targetId = kind == FileKind.SHORTCUT ? text(shortcut, "targetId") : null;
        String targetMime = kind == FileKind.SHORTCUT ? text(shortcut, "targetMimeType") : null;
        String name = text(node, "name");
        return new ProviderFile(id, name == null ? "" : name, kind, mimeType, size, parseInstant(text(node, "modifiedTime")),
                canDownload, kind == FileKind.FOLDER ? id : null,
                // headRevisionId and md5Checksum exist only for binary content (Drive docs); never invented.
                kind == FileKind.FILE ? text(node, "headRevisionId") : null,
                kind == FileKind.FILE ? text(node, "md5Checksum") : null,
                kind == FileKind.FILE && text(node, "md5Checksum") != null ? "md5" : null,
                StorageProvider.GOOGLE_DRIVE.isValidFileId(targetId) ? targetId : null, targetMime);
    }

    private JsonNode call(ProviderAccessToken token, URI uri) {
        Response response = transport.execute(() -> http.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, res) -> new Response(res.getStatusCode().value(),
                        res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), res.getBody().readAllBytes()), true), this);
        return parse(response.body());
    }

    /** Distinguishes authentication, scope, permission, rate-limit and quota failures. */
    @Override
    public ProviderException classify(Response response) {
        return classifyResponse(response);
    }

    static ProviderException classifyResponse(Response response) {
        int status = response.status();
        Duration retryAfter = ProviderHttp.parseRetryAfter(response.retryAfter());
        Set<String> reasons = reasons(response.body());
        if (status == 401) {
            return new ProviderException(Category.AUTHENTICATION, "Drive rejected the access token");
        }
        if (status == 429 || (status == 403 && reasons.stream().anyMatch(RATE_LIMIT_REASONS::contains))) {
            return new ProviderException(Category.RATE_LIMITED, "Drive rate limit reached", retryAfter);
        }
        if (status == 403 && reasons.stream().anyMatch(QUOTA_REASONS::contains)) {
            return new ProviderException(Category.RATE_LIMITED, "Drive quota exhausted", retryAfter);
        }
        if (status == 403 && reasons.stream().anyMatch(SCOPE_REASONS::contains)) {
            return new ProviderException(Category.INSUFFICIENT_SCOPE, "Drive grant lacks the required scope");
        }
        if (status == 403) {
            return new ProviderException(Category.PERMISSION_DENIED, "Drive denied access to this item");
        }
        if (status == 404) {
            return new ProviderException(Category.NOT_FOUND, "Drive item not found");
        }
        if (status == 400) {
            return new ProviderException(Category.INVALID_RESPONSE, "Drive rejected the request parameters");
        }
        if (status >= 500) {
            return new ProviderException(Category.UNAVAILABLE, "Drive returned a server error", retryAfter);
        }
        return new ProviderException(Category.INVALID_RESPONSE, "Drive returned an unexpected status " + status);
    }

    /** Per-minute rate limits and 5xx clear quickly; daily quota exhaustion does not. */
    @Override
    public boolean retryable(Response response, ProviderException failure) {
        if (failure.category() == Category.UNAVAILABLE) {
            return true;
        }
        return failure.category() == Category.RATE_LIMITED
                && (response.status() == 429 || reasons(response.body()).stream().anyMatch(RATE_LIMIT_REASONS::contains));
    }

    private static Set<String> reasons(byte[] body) {
        try {
            JsonNode error = JSON.readTree(body).path("error");
            Set<String> reasons = new HashSet<>();
            for (JsonNode e : error.path("errors")) {
                String reason = text(e, "reason");
                if (reason != null) {
                    reasons.add(reason);
                }
            }
            for (JsonNode d : error.path("details")) {
                String reason = text(d, "reason");
                if (reason != null) {
                    reasons.add(reason);
                }
            }
            return reasons;
        } catch (JacksonException | IllegalArgumentException e) {
            return Set.of();
        }
    }

    private static JsonNode parse(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new ProviderException(Category.INVALID_RESPONSE, "Drive response was not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new ProviderException(Category.INVALID_RESPONSE, "Drive response was not valid JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static Long parseSize(String value) {
        if (value == null) {
            return null;
        }
        try {
            long size = Long.parseLong(value);
            return size >= 0 ? size : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
}
