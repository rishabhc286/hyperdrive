package com.hyperdrive.backend.storage.connect;

import com.hyperdrive.backend.storage.provider.StorageProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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

/**
 * Provider-neutral OAuth authorization-code plumbing for storage consent, built from Spring
 * Security components: {@link DefaultOAuth2AuthorizationRequestResolver} (state, optional PKCE,
 * OIDC nonce when {@code openid} is requested) and Spring's authorization-code and refresh-token
 * clients. Nothing here touches HyperDrive sign-in or creates a session.
 */
public class StorageOAuth {

    /** What the browser leg needs. {@code nonceHash}/{@code codeVerifier} are null when unused. */
    public record AuthorizationStart(String authorizationUri, String state, String nonceHash, String codeVerifier) {
        @Override
        public String toString() {
            return "AuthorizationStart[redacted]";
        }
    }

    private final StorageProvider provider;
    private final ClientRegistrationRepository registrations;
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeClient;
    private final OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient;
    private final boolean pkce;

    /**
     * @param pkce            whether to send a PKCE S256 challenge (and the verifier on exchange)
     * @param extraParameters additional authorization-request parameters (e.g. offline access)
     */
    public StorageOAuth(StorageProvider provider, ClientRegistrationRepository registrations,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeClient,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient,
            boolean pkce, Map<String, String> extraParameters) {
        this.provider = provider;
        this.registrations = registrations;
        this.codeClient = codeClient;
        this.refreshClient = refreshClient;
        this.pkce = pkce;
        this.resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        Consumer<OAuth2AuthorizationRequest.Builder> extras =
                builder -> builder.additionalParameters(params -> params.putAll(extraParameters));
        // Spring Security may add PKCE by default. For providers where it is not wanted (not documented
        // together with a client secret), remove it explicitly so the request is exactly what we intend.
        Consumer<OAuth2AuthorizationRequest.Builder> noPkce = builder -> builder
                .additionalParameters(params -> {
                    params.remove(PkceParameterNames.CODE_CHALLENGE);
                    params.remove(PkceParameterNames.CODE_CHALLENGE_METHOD);
                })
                .attributes(attributes -> attributes.remove(PkceParameterNames.CODE_VERIFIER));
        this.resolver.setAuthorizationRequestCustomizer(pkce ? OAuth2AuthorizationRequestCustomizers.withPkce().andThen(extras)
                : extras.andThen(noPkce));
    }

    public StorageProvider provider() {
        return provider;
    }

    public boolean usesPkce() {
        return pkce;
    }

    public ClientRegistration registration() {
        ClientRegistration registration = registrations.findByRegistrationId(provider.registrationId());
        if (registration == null) {
            throw new IllegalStateException("Client registration for " + provider + " is missing");
        }
        return registration;
    }

    public AuthorizationStart start(HttpServletRequest request) {
        OAuth2AuthorizationRequest authorization = resolver.resolve(request, provider.registrationId());
        if (authorization == null) {
            throw new IllegalStateException("Client registration for " + provider + " is missing");
        }
        Object nonceHash = authorization.getAdditionalParameters().get(OidcParameterNames.NONCE);
        Object verifier = authorization.getAttribute(PkceParameterNames.CODE_VERIFIER);
        if (pkce && !(verifier instanceof String)) {
            throw new IllegalStateException("Authorization request lacks PKCE");
        }
        return new AuthorizationStart(authorization.getAuthorizationRequestUri(), authorization.getState(),
                nonceHash instanceof String n ? n : null, verifier instanceof String v ? v : null);
    }

    /** Exchanges the authorization code with Spring's token client (PKCE verifier when used). */
    public OAuth2AccessTokenResponse exchange(String code, String state, String codeVerifier) {
        ClientRegistration registration = registration();
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(state)
                .attributes(a -> {
                    if (codeVerifier != null) {
                        a.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
                    }
                })
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
                .redirectUri(registration.getRedirectUri())
                .state(state)
                .build();
        return codeClient.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
                new OAuth2AuthorizationExchange(request, response)));
    }

    /** Uses the refresh token; the provider may or may not return a new one. */
    public OAuth2AccessTokenResponse refresh(String accessToken, Instant accessTokenExpiresAt, String refreshToken) {
        Instant issued = accessTokenExpiresAt.minusSeconds(1);
        OAuth2AccessToken current = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, issued,
                accessTokenExpiresAt);
        return refreshClient.getTokenResponse(new OAuth2RefreshTokenGrantRequest(registration(), current,
                new OAuth2RefreshToken(refreshToken, null)));
    }

    /** True if the token response explicitly lists every one of {@code required}. */
    public static boolean grants(OAuth2AccessTokenResponse tokens, Set<String> required) {
        Set<String> scopes = tokens.getAccessToken().getScopes();
        return scopes != null && scopes.containsAll(required);
    }
}
