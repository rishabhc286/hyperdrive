package com.hyperdrive.backend.auth.desktop;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request/response DTOs for the desktop login handoff. Unknown JSON fields are rejected globally. */
public final class DesktopAuthRequests {

    private DesktopAuthRequests() {
    }

    /**
     * The desktop supplies only the loopback <em>port</em>; host (127.0.0.1), scheme (http) and path
     * (/callback) are fixed by the server, so no redirect URI is ever taken from a client.
     */
    public record CreateTransaction(
            @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String codeChallenge,
            @NotNull @Pattern(regexp = "^S256$") String codeChallengeMethod,
            @NotNull @Min(1024) @Max(65535) Integer callbackPort,
            @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{22,128}$") String callbackState,
            @Size(max = 64) @Pattern(regexp = "^[\\p{L}\\p{N} ._()'-]*$") String deviceName) {
    }

    public record TransactionCreated(UUID transactionId, String authorizationUrl, Instant expiresAt) {
    }

    public record Exchange(
            @NotNull UUID transactionId,
            @NotNull @Size(max = 64) String code,
            @NotNull @Pattern(regexp = "^[A-Za-z0-9._~-]{43,128}$") String codeVerifier) {

        @Override
        public String toString() {
            return "Exchange[transactionId=" + transactionId + "]";
        }
    }

    public record Refresh(@NotNull @Size(max = 64) String refreshToken) {
        @Override
        public String toString() {
            return "Refresh[redacted]";
        }
    }
}
