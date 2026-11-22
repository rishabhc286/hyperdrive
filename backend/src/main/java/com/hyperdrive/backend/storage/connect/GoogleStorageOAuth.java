package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.storage.provider.StorageProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Google OAuth for Drive storage consent, built entirely from Spring Security components:
 * {@link DefaultOAuth2AuthorizationRequestResolver} (state, OIDC nonce, PKCE), Spring's
 * authorization-code and refresh-token clients, and a JWT decoder with Spring's
 * {@code OidcIdTokenValidator}. Nothing here touches HyperDrive sign-in or creates a session.
 */
public class GoogleStorageOAuth {

    public static final String DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly";

    /** What the browser leg needs; the code verifier must be stored encrypted until the callback. */
    public record AuthorizationStart(String authorizationUri, String state, String nonceHash, String codeVerifier) {
        @Override
        public String toString() {
            return "AuthorizationStart[redacted]";
        }
    }

    /** The validated Google account behind a Drive grant. Email only if Google marked it verified. */
    public record ProviderIdentity(String issuer, String subject, String displayName, String email) {
        @Override
        public String toString() {
            return "ProviderIdentity[redacted]";
        }
    }

    public static final class IdentityValidationException extends RuntimeException {
        IdentityValidationException(String message) {
            super(message, null, false, false);
        }
    }

    private final ClientRegistrationRepository registrations;
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeClient;
    private final OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient;
    private final JwtDecoder idTokenDecoder;

    public GoogleStorageOAuth(ClientRegistrationRepository registrations,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeClient,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient,
            JwtDecoder idTokenDecoder) {
        this.registrations = registrations;
        this.codeClient = codeClient;
        this.refreshClient = refreshClient;
        this.idTokenDecoder = idTokenDecoder;
        this.resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        this.resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(params -> {
                    // Offline access: a refresh token so the connection outlives the 1-hour access token.
                    params.put("access_type", "offline");
                    // Let the user pick which Google account's Drive to connect (it may differ from
                    // the sign-in account), and re-show consent so a refresh token is issued again.
                    params.put("prompt", "select_account consent");
                })));
    }

    public ClientRegistration registration() {
        ClientRegistration registration = registrations.findByRegistrationId(StorageProvider.GOOGLE_DRIVE.registrationId());
        if (registration == null) {
            throw new IllegalStateException("Google Drive client registration is missing");
        }
        return registration;
    }

    public AuthorizationStart start(HttpServletRequest request) {
        OAuth2AuthorizationRequest authorization = resolver.resolve(request, StorageProvider.GOOGLE_DRIVE.registrationId());
        if (authorization == null) {
            throw new IllegalStateException("Google Drive client registration is missing");
        }
        Object nonceHash = authorization.getAdditionalParameters().get(OidcParameterNames.NONCE);
        Object verifier = authorization.getAttribute(PkceParameterNames.CODE_VERIFIER);
        if (!(nonceHash instanceof String n) || !(verifier instanceof String v)) {
            throw new IllegalStateException("Authorization request lacks nonce or PKCE");
        }
        return new AuthorizationStart(authorization.getAuthorizationRequestUri(), authorization.getState(), n, v);
    }

    /** Exchanges the authorization code with Spring's token client (PKCE verifier included). */
    public OAuth2AccessTokenResponse exchange(String code, String state, String codeVerifier) {
        ClientRegistration registration = registration();
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(state)
                .attributes(a -> a.put(PkceParameterNames.CODE_VERIFIER, codeVerifier))
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
                .redirectUri(registration.getRedirectUri())
                .state(state)
                .build();
        return codeClient.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
                new OAuth2AuthorizationExchange(request, response)));
    }

    /** Uses the refresh token; Google may or may not return a new one. */
    public OAuth2AccessTokenResponse refresh(String accessToken, Instant accessTokenExpiresAt, String refreshToken) {
        Instant expires = accessTokenExpiresAt;
        Instant issued = expires.minusSeconds(1);
        OAuth2AccessToken current = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, issued, expires);
        return refreshClient.getTokenResponse(new OAuth2RefreshTokenGrantRequest(registration(), current,
                new OAuth2RefreshToken(refreshToken, null)));
    }

    /**
     * Validates the ID token returned with the Drive grant (signature, issuer, audience, expiry via
     * Spring's validators) and the nonce bound to this transaction.
     */
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

    /** True if the token response explicitly lists the Drive read-only scope. */
    public static boolean grantsDriveReadonly(OAuth2AccessTokenResponse tokens) {
        Set<String> scopes = tokens.getAccessToken().getScopes();
        return scopes != null && scopes.contains(DRIVE_READONLY);
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
