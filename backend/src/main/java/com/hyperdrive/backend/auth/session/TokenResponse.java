package com.hyperdrive.backend.auth.session;

import com.hyperdrive.backend.user.UserAccount;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** Returned only by the exchange and refresh endpoints. {@code user} is present on exchange. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(
        String tokenType,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserAccount user) {

    public TokenResponse withUser(UserAccount account) {
        return new TokenResponse(tokenType, accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, account);
    }

    /** Never print token values (e.g. from accidental logging of this object). */
    @Override
    public String toString() {
        return "TokenResponse[tokenType=" + tokenType + ", accessTokenExpiresAt=" + accessTokenExpiresAt
                + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + ", user=" + user + "]";
    }
}
