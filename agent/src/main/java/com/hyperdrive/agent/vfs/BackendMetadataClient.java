package com.hyperdrive.agent.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls only three fixed backend routes with the mount's metadata grant:
 * {@code GET /api/v1/vfs/accounts}, {@code GET /api/v1/vfs/accounts/{id}/files} and (Batch 9)
 * {@code GET /api/v1/vfs/accounts/{id}/files/{fileId}}. The origin was
 * validated when the mount was created; redirects are refused; bodies are size-bounded and
 * strictly parsed. There is no generic forwarding.
 */
public final class BackendMetadataClient implements MetadataSource {

    static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Set<String> KINDS = Set.of("FILE", "FOLDER", "SHORTCUT", "PROVIDER_DOCUMENT");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final Duration requestTimeout;
    private final Clock clock;

    public BackendMetadataClient(Duration connectTimeout, Duration requestTimeout, Clock clock) {
        // HTTP/1.1 explicitly: no h2c upgrade negotiation on http:// loopback origins.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    @Override
    public List<Account> accounts(MountRegistry.Mount mount) throws MetadataException {
        JsonNode body = get(mount, "/api/v1/vfs/accounts");
        JsonNode items = body.get("items");
        if (items == null || !items.isArray() || items.size() > 1000) {
            throw invalid();
        }
        List<Account> out = new ArrayList<>();
        for (JsonNode item : items) {
            UUID id = uuid(item.get("id"));
            String provider = text(item, "provider", 32);
            if (id == null || !("GOOGLE_DRIVE".equals(provider) || "DROPBOX".equals(provider))) {
                throw invalid();
            }
            out.add(new Account(id, provider, text(item, "rootType", 32), text(item, "status", 32)));
        }
        return out;
    }

    @Override
    public Page files(MountRegistry.Mount mount, UUID accountId, String directory, String continuation, int pageSize)
            throws MetadataException {
        StringBuilder path = new StringBuilder("/api/v1/vfs/accounts/").append(accountId).append("/files?pageSize=")
                .append(pageSize);
        if (directory != null) {
            path.append("&directory=").append(URLEncoder.encode(directory, StandardCharsets.UTF_8));
        }
        if (continuation != null) {
            path.append("&continuation=").append(URLEncoder.encode(continuation, StandardCharsets.UTF_8));
        }
        JsonNode body = get(mount, path.toString());
        JsonNode items = body.get("items");
        if (items == null || !items.isArray()) {
            throw invalid();
        }
        List<Entry> entries = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            String id = text(item, "id", 512);
            String name = text(item, "name", 1024);
            String kind = text(item, "kind", 32);
            if (id == null || id.isEmpty() || name == null || kind == null || !KINDS.contains(kind)) {
                throw invalid();
            }
            JsonNode size = item.get("sizeBytes");
            Long sizeBytes = null;
            if (size != null && !size.isNull()) {
                if (!size.isIntegralNumber() || size.asLong() < 0) {
                    throw invalid();
                }
                sizeBytes = size.asLong();
            }
            Instant modified = null;
            String modifiedText = text(item, "modifiedAt", 64);
            if (modifiedText != null) {
                try {
                    modified = Instant.parse(modifiedText);
                } catch (DateTimeParseException e) {
                    throw invalid();
                }
            }
            entries.add(new Entry(id, name, kind, sizeBytes, modified, text(item, "directoryLocator", 4096)));
        }
        String next = text(body, "continuation", 8192);
        return new Page(entries, next);
    }

    @Override
    public FileMeta file(MountRegistry.Mount mount, UUID accountId, String fileId) throws MetadataException {
        JsonNode body = get(mount, "/api/v1/vfs/accounts/" + accountId + "/files/"
                + URLEncoder.encode(fileId, StandardCharsets.UTF_8).replace("+", "%20"));
        String id = text(body, "id", 512);
        String provider = text(body, "provider", 32);
        String kind = text(body, "kind", 32);
        JsonNode size = body.get("sizeBytes");
        JsonNode download = body.get("canDownload");
        if (id == null || !id.equals(fileId) || !("GOOGLE_DRIVE".equals(provider) || "DROPBOX".equals(provider))
                || kind == null || !KINDS.contains(kind) || download == null || !download.isBoolean()
                || (size != null && !size.isNull() && (!size.isIntegralNumber() || !size.canConvertToLong()
                        || size.asLong() < 0))) {
            throw invalid();
        }
        Long sizeBytes = size == null || size.isNull() ? null : size.asLong();
        return new FileMeta(id, provider, kind, sizeBytes, text(body, "providerRevision", 256), download.asBoolean());
    }

    private JsonNode get(MountRegistry.Mount mount, String pathAndQuery) throws MetadataException {
        if (!mount.grantExpiresAt().isAfter(clock.instant())) {
            throw new MetadataException("authorization_expired", 401);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(mount.backendOrigin + pathAndQuery))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + mount.grant())
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new MetadataException("backend_unavailable", 503);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataException("cancelled", 503);
        }
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes(MAX_BODY + 1);
        } catch (IOException e) {
            throw new MetadataException("backend_unavailable", 503);
        }
        int status = response.statusCode();
        if (status != 200) {
            String code = errorCode(bytes);
            throw switch (status) {
                case 401 -> new MetadataException("authorization_lost", 401);
                case 403 -> new MetadataException("forbidden", 403);
                case 404 -> new MetadataException("not_found", 404);
                case 409 -> new MetadataException("account_needs_reconnect".equals(code) ? "needs_reconnect" : "conflict", 409);
                case 429 -> new MetadataException("rate_limited", 429);
                default -> new MetadataException(status >= 500 ? "backend_unavailable" : "backend_error", 502);
            };
        }
        if (bytes.length > MAX_BODY) {
            throw new MetadataException("response_too_large", 502);
        }
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw invalid();
            }
            return node;
        } catch (JacksonException e) {
            throw invalid();
        }
    }

    private static String errorCode(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode code = node == null ? null : node.get("code");
            return code != null && code.isString() ? code.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static MetadataException invalid() {
        return new MetadataException("invalid_backend_response", 502);
    }

    private static String text(JsonNode node, String field, int max) throws MetadataException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.asString().length() > max) {
            throw invalid();
        }
        return value.asString();
    }

    private static UUID uuid(JsonNode node) {
        try {
            return node != null && node.isString() ? UUID.fromString(node.asString()) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
