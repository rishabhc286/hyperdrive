package com.hyperdrive.agent.read;

import com.hyperdrive.agent.read.CredentialRegistry.Credential;
import com.hyperdrive.agent.read.CredentialRegistry.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one validated byte range directly from Google Drive or Dropbox with a delegated access
 * token. Requests are built only from {@link ProviderEndpoints} and validated ids; redirects are
 * never followed (so the Authorization header can't reach another origin); bodies are read with a
 * hard cap and closed early when a provider ignores the range.
 *
 * <p>Endpoints (provider docs, read 2026-11-22):
 * <ul>
 *   <li>Google: {@code GET /drive/v3/files/{id}?alt=media} with {@code Range: bytes=a-b}. Not
 *       revision-pinned: Drive only serves "Keep Forever" revisions via revisions.get.</li>
 *   <li>Dropbox: {@code POST content.dropboxapi.com/2/files/download} with {@code Dropbox-API-Arg}
 *       {@code {"path":"rev:<rev>"}} (or {@code "id:..."}), {@code Dropbox-API-Path-Root} and
 *       {@code Range}; a pinned {@code rev:} read returns exactly that revision.</li>
 * </ul>
 */
public final class ProviderReader implements RangeReader {

    private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+)$");
    private static final Pattern UNSATISFIED = Pattern.compile("^bytes \\*/(\\d+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(2);

    /** Waits between retries; replaceable in tests. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public record Result(byte[] data, int length, boolean revisionPinned, String revision) {
    }

    private final HttpClient http;
    private final ProviderEndpoints endpoints;
    private final Duration requestTimeout;
    private final Sleeper sleeper;

    public ProviderReader(ProviderEndpoints endpoints, Duration connectTimeout, Duration requestTimeout, Sleeper sleeper) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.endpoints = endpoints;
        this.requestTimeout = requestTimeout;
        this.sleeper = sleeper;
    }

    /**
     * Reads {@code [start, endInclusive]} of a file whose expected total size is {@code expectedSize}.
     * The caller has already clamped the range to the expected size.
     */
    @Override
    public Result read(Credential credential, String fileId, long start, long endInclusive, long expectedSize,
            String expectedRevision, CancelToken cancel) throws ReadException {
        int wanted = Math.toIntExact(endInclusive - start + 1);
        ReadException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (cancel.isCancelled()) {
                throw new ReadException("cancelled", 409);
            }
            try {
                return attempt(credential, fileId, start, endInclusive, wanted, expectedSize, expectedRevision, cancel);
            } catch (Retryable r) {
                last = r.failure;
                Duration wait = r.failure.retryAfter() != null ? r.failure.retryAfter()
                        : Duration.ofMillis(200L << attempt);
                if (attempt + 1 >= MAX_ATTEMPTS || wait.compareTo(MAX_RETRY_WAIT) > 0) {
                    throw r.failure;
                }
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ReadException("cancelled", 409);
                }
            }
        }
        throw last;
    }

    /** A failure worth retrying (transient or a short rate limit). */
    private static final class Retryable extends Exception {
        final ReadException failure;

        Retryable(ReadException failure) {
            super(null, null, false, false);
            this.failure = failure;
        }
    }

    private Result attempt(Credential credential, String fileId, long start, long end, int wanted, long expectedSize,
            String expectedRevision, CancelToken cancel) throws ReadException, Retryable {
        HttpRequest request = request(credential, fileId, start, end, expectedRevision);
        CompletableFuture<HttpResponse<InputStream>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        cancel.attach(future);
        HttpResponse<InputStream> response;
        try {
            response = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Retryable(new ReadException("timeout", 504));
        } catch (CancellationException e) {
            throw new ReadException("cancelled", 409);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReadException("cancelled", 409);
        } catch (ExecutionException e) {
            if (cancel.isCancelled()) {
                throw new ReadException("cancelled", 409);
            }
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) {
                throw new Retryable(new ReadException("timeout", 504));
            }
            throw new Retryable(new ReadException("unavailable", 502));
        }
        try (InputStream body = response.body()) {
            cancel.attach(body);
            int status = response.statusCode();
            // We asked for identity; a compressed body would make byte offsets meaningless.
            String encoding = response.headers().firstValue("Content-Encoding").orElse("identity").trim();
            if ((status == 200 || status == 206) && !encoding.equalsIgnoreCase("identity")) {
                throw new ReadException("invalid_provider_response", 502);
            }
            if (status == 206) {
                return partial(response, body, credential, start, end, wanted, expectedSize, expectedRevision);
            }
            if (status == 200) {
                return whole(response, body, credential, start, end, wanted, expectedSize, expectedRevision);
            }
            if (status == 416) {
                String unsatisfied = response.headers().firstValue("Content-Range").orElse("");
                Matcher m = UNSATISFIED.matcher(unsatisfied);
                throw m.matches() && Long.parseLong(m.group(1)) != expectedSize
                        ? new ReadException("file_changed", 409) : new ReadException("invalid_range", 416);
            }
            if (status >= 300 && status < 400) {
                // Never followed: the token must not be forwarded to wherever Location points.
                throw new ReadException("redirect_refused", 502);
            }
            throw classify(credential.provider, status, readError(body), retryAfter(response));
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new ReadException("cancelled", 409);
            }
            throw new Retryable(new ReadException("unavailable", 502));
        }
    }

    private HttpRequest request(Credential credential, String fileId, long start, long end, String expectedRevision) {
        HttpRequest.Builder b;
        if (credential.provider == Provider.GOOGLE_DRIVE) {
            URI uri = URI.create(endpoints.googleDriveApi() + "/files/"
                    + URLEncoder.encode(fileId, StandardCharsets.UTF_8) + "?alt=media&supportsAllDrives=true");
            b = HttpRequest.newBuilder(uri).GET();
        } else {
            URI uri = URI.create(endpoints.dropboxContentApi() + "/files/download");
            Map<String, String> arg = new LinkedHashMap<>();
            // A rev: path reads exactly that revision (Dropbox files.stone ReadPath); ids are ASCII.
            arg.put("path", expectedRevision != null ? "rev:" + expectedRevision : fileId);
            Map<String, String> root = new LinkedHashMap<>();
            root.put(".tag", "root");
            root.put("root", credential.rootNamespaceId);
            b = HttpRequest.newBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Dropbox-API-Arg", JSON.writeValueAsString(arg))
                    .header("Dropbox-API-Path-Root", JSON.writeValueAsString(root));
        }
        return b.timeout(requestTimeout)
                .header("Authorization", "Bearer " + credential.accessToken)
                .header("Range", "bytes=" + start + "-" + end)
                .header("Accept-Encoding", "identity")
                .build();
    }

    /** 206: Content-Range must match exactly, the total must be the expected size, the body exact. */
    private Result partial(HttpResponse<InputStream> response, InputStream body, Credential credential, long start,
            long end, int wanted, long expectedSize, String expectedRevision) throws ReadException, IOException {
        Matcher m = CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
        if (!m.matches()) {
            throw new ReadException("invalid_provider_response", 502);
        }
        long rStart = Long.parseLong(m.group(1));
        long rEnd = Long.parseLong(m.group(2));
        long total = Long.parseLong(m.group(3));
        if (total != expectedSize) {
            throw new ReadException("file_changed", 409);
        }
        if (rStart != start || rEnd != end) {
            throw new ReadException("invalid_provider_response", 502);
        }
        String revision = checkRevision(response, credential, expectedSize, expectedRevision);
        byte[] data = readExactly(body, wanted);
        return new Result(data, wanted, pinned(credential, expectedRevision), revision);
    }

    /**
     * 200 means the range was ignored. Accepted only when the request already covered the whole file
     * ({@code start == 0 && end == size-1}) so the full body is exactly what was asked for, within the
     * 4 MiB bound. Anything else is closed without consuming the body.
     */
    private Result whole(HttpResponse<InputStream> response, InputStream body, Credential credential, long start,
            long end, int wanted, long expectedSize, String expectedRevision) throws ReadException, IOException {
        if (start != 0 || end != expectedSize - 1) {
            throw new ReadException("range_not_supported", 502);
        }
        Optional<String> length = response.headers().firstValue("Content-Length");
        if (length.isPresent() && !length.get().equals(Long.toString(expectedSize))) {
            throw length.get().matches("\\d+") ? new ReadException("file_changed", 409)
                    : new ReadException("invalid_provider_response", 502);
        }
        String revision = checkRevision(response, credential, expectedSize, expectedRevision);
        byte[] data = readExactly(body, wanted);
        return new Result(data, wanted, pinned(credential, expectedRevision), revision);
    }

    private static boolean pinned(Credential credential, String expectedRevision) {
        return credential.provider == Provider.DROPBOX && expectedRevision != null;
    }

    /** Dropbox reports the served file's metadata in Dropbox-API-Result; check rev and size against it. */
    private static String checkRevision(HttpResponse<InputStream> response, Credential credential, long expectedSize,
            String expectedRevision) throws ReadException {
        if (credential.provider != Provider.DROPBOX) {
            return null;
        }
        Optional<String> header = response.headers().firstValue("Dropbox-API-Result");
        if (header.isEmpty()) {
            return null;
        }
        try {
            JsonNode result = JSON.readTree(header.get());
            String rev = result.path("rev").asString(null);
            JsonNode size = result.get("size");
            if (expectedRevision != null && rev != null && !expectedRevision.equals(rev)) {
                throw new ReadException("file_changed", 409);
            }
            if (size != null && size.isIntegralNumber() && size.asLong() != expectedSize) {
                throw new ReadException("file_changed", 409);
            }
            return rev != null && rev.matches("[0-9a-f]{9,64}") ? rev : null;
        } catch (JacksonException e) {
            throw new ReadException("invalid_provider_response", 502);
        }
    }

    /**
     * Reads exactly {@code wanted} bytes. More bytes than announced → error (without reading them);
     * fewer → truncated. Memory is bounded by {@code wanted} (≤ 4 MiB).
     */
    static byte[] readExactly(InputStream in, int wanted) throws ReadException, IOException {
        byte[] data = new byte[wanted];
        int read = 0;
        while (read < wanted) {
            int n = in.read(data, read, wanted - read);
            if (n < 0) {
                throw new ReadException("truncated_response", 502);
            }
            read += n;
        }
        if (in.read() != -1) {
            throw new ReadException("invalid_provider_response", 502);
        }
        return data;
    }

    private static ReadException classify(Provider provider, int status, JsonNode error, Duration retryAfter)
            throws Retryable {
        if (status == 401) {
            String tag = error == null ? null : error.path("error").path(".tag").asString(null);
            return "missing_scope".equals(tag) ? new ReadException("permission_missing", 409)
                    : new ReadException("credential_rejected", 409);
        }
        if (status == 429) {
            throw new Retryable(new ReadException("rate_limited", 429, retryAfter));
        }
        if (status == 403 && provider == Provider.GOOGLE_DRIVE) {
            String reason = error == null ? "" : firstReason(error);
            if (reason.equals("rateLimitExceeded") || reason.equals("userRateLimitExceeded")) {
                throw new Retryable(new ReadException("rate_limited", 429, retryAfter));
            }
            if (reason.equals("fileNotDownloadable")) {
                return new ReadException("unsupported_file", 415);
            }
            return new ReadException("forbidden", 403);
        }
        if (status == 403) {
            return new ReadException("forbidden", 403);
        }
        if (status == 404) {
            return new ReadException("not_found", 404);
        }
        if (status == 409 && provider == Provider.DROPBOX && error != null) {
            JsonNode e = error.path("error");
            String tag = e.path(".tag").asString("");
            if (tag.equals("unsupported_file")) {
                return new ReadException("unsupported_file", 415);
            }
            String lookup = e.path("path").path(".tag").asString("");
            if (lookup.equals("not_found")) {
                return new ReadException("not_found", 404);
            }
            if (lookup.equals("restricted_content") || lookup.equals("not_file")) {
                return new ReadException("forbidden", 403);
            }
        }
        if (status == 422 && provider == Provider.DROPBOX) {
            return new ReadException("forbidden", 403);
        }
        if (status >= 500) {
            throw new Retryable(new ReadException("unavailable", 502, retryAfter));
        }
        return new ReadException("provider_error", 502);
    }

    private static String firstReason(JsonNode error) {
        for (JsonNode e : error.path("error").path("errors")) {
            String r = e.path("reason").asString(null);
            if (r != null) {
                return r;
            }
        }
        return "";
    }

    /** Error bodies are small JSON documents; anything large or non-JSON is ignored. */
    private static JsonNode readError(InputStream body) {
        try {
            byte[] bytes = body.readNBytes(16 * 1024);
            JsonNode node = JSON.readTree(bytes);
            return node != null && node.isObject() ? node : null;
        } catch (IOException | JacksonException e) {
            return null;
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").filter(v -> v.matches("\\d{1,6}"))
                .map(v -> Duration.ofSeconds(Long.parseLong(v))).orElse(null);
    }
}
