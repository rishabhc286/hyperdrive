package com.hyperdrive.backend.storage;

import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.account.ConnectedAccountService;
import com.hyperdrive.backend.storage.account.ProviderCredentialStore;
import com.hyperdrive.backend.storage.connect.DropboxConnectionCompleter;
import com.hyperdrive.backend.storage.connect.GoogleStorageOAuth;
import com.hyperdrive.backend.storage.connect.StorageBrowserController;
import com.hyperdrive.backend.storage.connect.StorageConnectionService;
import com.hyperdrive.backend.storage.connect.StorageOAuth;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.storage.provider.dropbox.DropboxBrowser;
import com.hyperdrive.backend.storage.provider.google.GoogleDriveBrowser;
import java.net.http.HttpClient;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Wiring for cloud-storage connections. Token clients and the ID-token decoder are deliberately not
 * exposed as beans, so Spring Security's sign-in configuration cannot pick them up by type.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuthEnabled
public class StorageConfig {

    /** Fails startup with an actionable message if the key is missing or malformed. */
    @Bean
    CredentialCipher credentialCipher(StorageProperties properties) {
        return CredentialCipher.fromConfiguration(properties.credentialKey(), properties.credentialKeyVersion(),
                properties.previousCredentialKeys());
    }

    @Bean
    GoogleStorageOAuth googleStorageOAuth(ClientRegistrationRepository registrations, StorageProperties properties) {
        RestClient tokenHttp = tokenRestClient(properties);
        ClientRegistration registration = registrations.findByRegistrationId(StorageProvider.GOOGLE_DRIVE.registrationId());
        if (registration == null) {
            throw new IllegalStateException("Google Drive client registration 'google-drive' is missing");
        }
        SimpleClientHttpRequestFactory jwksFactory = new SimpleClientHttpRequestFactory();
        jwksFactory.setConnectTimeout(properties.connectTimeout());
        jwksFactory.setReadTimeout(properties.readTimeout());
        NimbusJwtDecoder idTokenDecoder = NimbusJwtDecoder
                .withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                .restOperations(new RestTemplate(jwksFactory))
                .build();
        // Spring's OIDC ID-token rules: issuer, audience/azp = our client id, exp/iat; plus clock skew.
        idTokenDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
        return new GoogleStorageOAuth(registrations, codeClient(tokenHttp), refreshClient(tokenHttp), idTokenDecoder);
    }

    @Bean
    GoogleDriveBrowser googleDriveBrowser(StorageProperties properties) {
        RestClient http = RestClient.builder().requestFactory(requestFactory(properties)).build();
        return new GoogleDriveBrowser(http, properties, duration -> Thread.sleep(duration));
    }

    /** Dropbox is optional: these beans exist only when DROPBOX_APP_KEY and DROPBOX_APP_SECRET are set. */
    @Configuration(proxyBeanMethods = false)
    @Conditional(DropboxConfigured.class)
    static class DropboxConfig {

        @Bean
        StorageOAuth dropboxStorageOAuth(StorageProperties properties, AuthProperties auth) {
            StorageProperties.Dropbox dropbox = properties.dropbox();
            ClientRegistration registration = ClientRegistration.withRegistrationId(StorageProvider.DROPBOX.registrationId())
                    .clientName("Dropbox")
                    .clientId(dropbox.appKey())
                    .clientSecret(dropbox.appSecret())
                    // Dropbox documents HTTP Basic (app key / secret) for the token endpoint.
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(auth.publicBaseUrl().toString().replaceAll("/$", "")
                            + StorageBrowserController.callbackPath(StorageProvider.DROPBOX))
                    // Identity + metadata only. Content read and every write scope are not requested.
                    .scope(DropboxConnectionCompleter.SCOPE_ACCOUNT_INFO, DropboxConnectionCompleter.SCOPE_METADATA)
                    .authorizationUri(dropbox.authorizationUri().toString())
                    .tokenUri(dropbox.tokenUri().toString())
                    .build();
            RestClient tokenHttp = tokenRestClient(properties);
            // token_access_type=offline: Dropbox returns a refresh token (documented). No PKCE: its use
            // together with a client secret is not documented, so it is not relied on.
            return new StorageOAuth(StorageProvider.DROPBOX, new InMemoryClientRegistrationRepository(registration),
                    codeClient(tokenHttp), refreshClient(tokenHttp), false, Map.of("token_access_type", "offline"));
        }

        @Bean
        DropboxBrowser dropboxBrowser(StorageProperties properties) {
            RestClient http = RestClient.builder().requestFactory(requestFactory(properties)).build();
            return new DropboxBrowser(http, properties, duration -> Thread.sleep(duration));
        }

        @Bean
        DropboxConnectionCompleter dropboxConnectionCompleter(StorageProperties properties, AuthProperties auth,
                StorageConnectionService transactions, ConnectedAccountService accounts,
                ProviderCredentialStore credentials, DropboxBrowser dropboxBrowser, TransactionTemplate tx,
                java.util.List<StorageOAuth> oauthClients) {
            StorageOAuth oauth = oauthClients.stream().filter(o -> o.provider() == StorageProvider.DROPBOX).findFirst()
                    .orElseThrow();
            return new DropboxConnectionCompleter(oauth, transactions, accounts, credentials, dropboxBrowser, tx);
        }
    }

    /** True when both Dropbox app credentials are present (partial config fails in StorageStartupCheck). */
    static final class DropboxConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("hyperdrive.storage.dropbox.app-key", "");
            String secret = context.getEnvironment().getProperty("hyperdrive.storage.dropbox.app-secret", "");
            return !key.isBlank() && !secret.isBlank() && !key.contains("${") && !secret.contains("${");
        }
    }

    /** Same converters and error handler Spring Security uses by default, plus our timeouts. */
    private static RestClient tokenRestClient(StorageProperties properties) {
        return RestClient.builder()
                .requestFactory(requestFactory(properties))
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    private static RestClientAuthorizationCodeTokenResponseClient codeClient(RestClient http) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(http);
        return client;
    }

    private static RestClientRefreshTokenTokenResponseClient refreshClient(RestClient http) {
        RestClientRefreshTokenTokenResponseClient client = new RestClientRefreshTokenTokenResponseClient();
        client.setRestClient(http);
        return client;
    }

    private static JdkClientHttpRequestFactory requestFactory(StorageProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
