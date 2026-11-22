package com.hyperdrive.backend.user;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Username rules. A username is a public profile handle, never a credential.
 * 3–24 ASCII letters, digits or underscores; unique case-insensitively; some names reserved.
 */
public final class UsernamePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 24;
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_]{3,24}$");

    /** Documented in docs/authentication.md. Compared after normalization. */
    public static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "support", "system", "hyperdrive", "root", "security", "help");

    public enum Violation { INVALID_FORMAT, RESERVED }

    private UsernamePolicy() {
    }

    public static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public static Optional<Violation> check(String username) {
        if (username == null || !FORMAT.matcher(username).matches()) {
            return Optional.of(Violation.INVALID_FORMAT);
        }
        if (RESERVED.contains(normalize(username))) {
            return Optional.of(Violation.RESERVED);
        }
        return Optional.empty();
    }
}
