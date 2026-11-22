package com.hyperdrive.backend.storage;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.config.StorageProperties;
import com.hyperdrive.backend.storage.connect.GoogleStorageOAuth;
import com.hyperdrive.backend.storage.crypto.CredentialCipher;
import com.hyperdrive.backend.storage.provider.FileBrowser;
import com.hyperdrive.backend.storage.provider.StorageProvider;
import com.hyperdrive.backend.storage.provider.google.GoogleDriveBrowser;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
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
        JdkClientHttpRequestFactory factory = requestFactory(properties);
        RestClient tokenHttp = RestClient.builder()
                .requestFactory(factory)
                // Same converters and error handler Spring Security uses by default, plus timeouts.
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientAuthorizationCodeTokenResponseClient codeClient = new RestClientAuthorizationCodeTokenResponseClient();
        codeClient.setRestClient(tokenHttp);
        RestClientRefreshTokenTokenResponseClient refreshClient = new RestClientRefreshTokenTokenResponseClient();
        refreshClient.setRestClient(tokenHttp);

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
        return new GoogleStorageOAuth(registrations, codeClient, refreshClient, idTokenDecoder);
    }

    @Bean
    FileBrowser googleDriveBrowser(StorageProperties properties) {
        RestClient http = RestClient.builder().requestFactory(requestFactory(properties)).build();
        return new GoogleDriveBrowser(http, properties, duration -> Thread.sleep(duration));
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
