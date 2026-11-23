package com.hyperdrive.agent.vfs;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The virtual drive's scoped authorization inside the agent (Batch 8).
 *
 * <p>Electron (with the agent's administration secret) creates <b>one</b> mount at a time. Each
 * mount gets:
 * <ul>
 *   <li>a new <b>generation</b> number, and</li>
 *   <li>a fresh 256-bit <b>mount capability</b> ({@code hdv_...}) that is returned once and stored
 *       here only as a SHA-256 hash. It authorizes nothing but the read-only VFS metadata routes.</li>
 * </ul>
 * Creating a new mount, revoking, or an agent restart (memory only) makes every older capability
 * fail. The backend metadata grant ({@code hdm_...}) is held here for the mount and never returned
 * to the VFS.
 */
public final class MountRegistry {

    public static final Pattern GRANT = Pattern.compile("^hdm_[A-Za-z0-9_-]{43}$");
    static final Pattern CAPABILITY = Pattern.compile("^hdv_[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A live mount. {@code toString} never shows the grant. */
    public static final class Mount {
        final long generation;
        final UUID userId;
        final URI backendOrigin;
        private final byte[] capabilityHash;
        private volatile String grant;
        private volatile Instant grantExpiresAt;

        Mount(long generation, UUID userId, URI backendOrigin, byte[] capabilityHash, String grant, Instant expiresAt) {
            this.generation = generation;
            this.userId = userId;
            this.backendOrigin = backendOrigin;
            this.capabilityHash = capabilityHash;
            this.grant = grant;
            this.grantExpiresAt = expiresAt;
        }

        public long generation() {
            return generation;
        }

        String grant() {
            return grant;
        }

        Instant grantExpiresAt() {
            return grantExpiresAt;
        }

        @Override
        public String toString() {
            return "Mount[generation=" + generation + "]";
        }
    }

    public record Created(long generation, String capability) {
        @Override
        public String toString() {
            return "Created[generation=" + generation + "]";
        }
    }

    public static final class InvalidMountException extends Exception {
        private final String code;

        InvalidMountException(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Clock clock;
    private long nextGeneration = 1;
    private Mount current;

    public MountRegistry(Clock clock) {
        this.clock = clock;
    }

    /** Replaces any existing mount (its capability stops working immediately). */
    public synchronized Created create(UUID userId, String backendOrigin, String grant, Instant grantExpiresAt)
            throws InvalidMountException {
        URI origin = validateOrigin(backendOrigin);
        validateGrant(grant, grantExpiresAt);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String capability = "hdv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long generation = nextGeneration++;
        current = new Mount(generation, userId, origin, sha256(capability), grant, grantExpiresAt);
        return new Created(generation, capability);
    }

    /** The desktop renews the short-lived backend grant while mounted. */
    public synchronized void renewGrant(long generation, String grant, Instant expiresAt) throws InvalidMountException {
        if (current == null || current.generation != generation) {
            throw new InvalidMountException("mount_not_found");
        }
        validateGrant(grant, expiresAt);
        current.grant = grant;
        current.grantExpiresAt = expiresAt;
    }

    /** Unmount, logout or shutdown. Returns whether a mount existed. */
    public synchronized boolean revoke() {
        boolean had = current != null;
        current = null;
        return had;
    }

    public synchronized Mount current() {
        return current;
    }

    /** The mount for an {@code Authorization: Bearer hdv_...} header, or null. Constant-time compare. */
    public Mount authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String capability = authorizationHeader.substring("Bearer ".length());
        if (!CAPABILITY.matcher(capability).matches()) {
            return null;
        }
        Mount mount = current();
        if (mount == null) {
            return null;
        }
        return MessageDigest.isEqual(mount.capabilityHash, sha256(capability)) ? mount : null;
    }

    private void validateGrant(String grant, Instant expiresAt) throws InvalidMountException {
        if (grant == null || !GRANT.matcher(grant).matches()) {
            throw new InvalidMountException("invalid_grant");
        }
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new InvalidMountException("grant_expired");
        }
    }

    /** https anywhere, or http to a loopback host only; origin only (no path, query, userinfo). */
    static URI validateOrigin(String value) throws InvalidMountException {
        URI uri;
        try {
            uri = value == null || value.length() > 200 ? null : new URI(value);
        } catch (java.net.URISyntaxException e) {
            uri = null;
        }
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
            throw new InvalidMountException("invalid_backend_origin");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
        if (!("https".equals(scheme) || ("http".equals(scheme) && loopback))) {
            throw new InvalidMountException("invalid_backend_origin");
        }
        int port = uri.getPort();
        return URI.create(scheme + "://" + uri.getHost() + (port > 0 ? ":" + port : ""));
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
