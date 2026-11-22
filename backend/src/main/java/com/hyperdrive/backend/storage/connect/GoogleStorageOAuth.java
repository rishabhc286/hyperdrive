package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.storage.provider.ProviderIdentity;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Google Drive storage consent: PKCE, offline access, account chooser, and ID-token identity
 * (signature, issuer, audience, expiry via Spring's validators, plus the transaction nonce).
 */
public class GoogleStorageOAuth extends StorageOAuth {

    public static final String DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly";

    public static final class IdentityValidationException extends RuntimeException {
        IdentityValidationException(String message) {
            super(message, null, false, false);
        }
    }

    private final JwtDecoder idTokenDecoder;

    public GoogleStorageOAuth(ClientRegistrationRepository registrations,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeClient,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient,
            JwtDecoder idTokenDecoder) {
        // Offline access: a refresh token so the connection outlives the 1-hour access token.
        // select_account consent: the user picks which Google account's Drive to connect (it may
        // differ from the sign-in account), and consent is shown again so a refresh token is issued.
        super(StorageProvider.GOOGLE_DRIVE, registrations, codeClient, refreshClient, true,
                Map.of("access_type", "offline", "prompt", "select_account consent"));
        this.idTokenDecoder = idTokenDecoder;
    }

    public ProviderIdentity identity(OAuth2AccessTokenResponse tokens, String expectedNonceHash) {
        Object raw = tokens.getAdditionalParameters().get(OidcParameterNames.ID_TOKEN);
        if (!(raw instanceof String idToken)) {
            throw new IdentityValidationException("No ID token in the Drive grant");
        }
        Jwt jwt;
        try {
            jwt = idTokenDecoder.decode(idToken);
        } catch (JwtException e) {
            throw new IdentityValidationException("ID token failed validation");
        }
        if (expectedNonceHash == null || !expectedNonceHash.equals(jwt.getClaimAsString("nonce"))) {
            throw new IdentityValidationException("ID token nonce does not match the transaction");
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new IdentityValidationException("ID token has no usable subject");
        }
        Boolean verified = jwt.getClaimAsBoolean("email_verified");
        String email = Boolean.TRUE.equals(verified) ? jwt.getClaimAsString("email") : null;
        String name = jwt.getClaimAsString("name");
        return new ProviderIdentity(jwt.getIssuer().toString(), subject, truncate(name, 200), truncate(email, 320));
    }

    public static boolean grantsDriveReadonly(OAuth2AccessTokenResponse tokens) {
        return grants(tokens, Set.of(DRIVE_READONLY));
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
