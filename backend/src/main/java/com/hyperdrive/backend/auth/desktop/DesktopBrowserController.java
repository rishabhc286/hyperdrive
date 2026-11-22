package com.hyperdrive.backend.auth.desktop;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Browser entry point of desktop sign-in. Opened by the system browser (never by the renderer).
 * Starts Google sign-in for exactly one pending transaction.
 */
@Controller
@ConditionalOnAuthEnabled
public class DesktopBrowserController {

    public static final String AUTHORIZE_PATH = "/api/v1/auth/desktop/authorize";
    public static final String GOOGLE = "google";

    private final LoginTransactionService transactions;
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final TransactionBoundAuthorizationRequests.Repository authorizationRequests;

    public DesktopBrowserController(LoginTransactionService transactions, ClientRegistrationRepository registrations,
            TransactionBoundAuthorizationRequests.Repository authorizationRequests) {
        this.transactions = transactions;
        this.authorizationRequests = authorizationRequests;
        // Standard Spring resolver: adds OAuth state and OIDC nonce; PKCE added for Google as defense in depth.
        this.resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        this.resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    }

    @GetMapping(AUTHORIZE_PATH + "/{transactionId}")
    public void authorize(@PathVariable String transactionId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        UUID id = parse(transactionId);
        if (id == null || !transactions.startBrowserLogin(id)) {
            BrowserPages.invalidLink(response);
            return;
        }
        OAuth2AuthorizationRequest authorizationRequest = resolver.resolve(request, GOOGLE);
        if (authorizationRequest == null) {
            throw new IllegalStateException("Google client registration is missing");
        }
        OAuth2AuthorizationRequest bound = TransactionBoundAuthorizationRequests.bind(authorizationRequest, id);
        authorizationRequests.saveAuthorizationRequest(bound, request, response);
        BrowserPages.redirect(response, URI.create(bound.getAuthorizationRequestUri()));
    }

    private static UUID parse(String value) {
        try {
            return value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
