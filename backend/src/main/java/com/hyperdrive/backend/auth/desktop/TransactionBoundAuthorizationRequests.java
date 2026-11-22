package com.hyperdrive.backend.auth.desktop;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Binds a desktop login transaction to Spring Security's own OAuth authorization request.
 *
 * <p>The transaction id is stored as an attribute <em>inside</em> the saved authorization request,
 * which Spring keys by its OAuth {@code state}. When Google calls back, Spring removes exactly that
 * request after validating {@code state}; this repository then exposes the transaction id as a
 * request attribute for the success/failure handler. There is no global or per-session "current
 * transaction", so login attempts cannot be mixed up.
 */
public final class TransactionBoundAuthorizationRequests {

    static final String TRANSACTION_ATTRIBUTE = "hyperdrive_login_transaction";
    private static final String REQUEST_ATTRIBUTE = TransactionBoundAuthorizationRequests.class.getName() + ".transaction";

    private TransactionBoundAuthorizationRequests() {
    }

    public static OAuth2AuthorizationRequest bind(OAuth2AuthorizationRequest request, UUID transactionId) {
        return OAuth2AuthorizationRequest.from(request)
                .attributes(attributes -> attributes.put(TRANSACTION_ATTRIBUTE, transactionId.toString()))
                .build();
    }

    /** The transaction bound to the authorization request Spring just consumed, if any. */
    public static UUID transactionOf(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String s ? UUID.fromString(s) : null;
    }

    /** Session-backed repository that surfaces the bound transaction id on removal. */
    public static final class Repository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

        private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
                new HttpSessionOAuth2AuthorizationRequestRepository();

        @Override
        public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
            return delegate.loadAuthorizationRequest(request);
        }

        @Override
        public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
                HttpServletResponse response) {
            delegate.saveAuthorizationRequest(authorizationRequest, request, response);
        }

        @Override
        public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                HttpServletResponse response) {
            OAuth2AuthorizationRequest removed = delegate.removeAuthorizationRequest(request, response);
            if (removed != null && removed.getAttribute(TRANSACTION_ATTRIBUTE) instanceof String transactionId) {
                request.setAttribute(REQUEST_ATTRIBUTE, transactionId);
            }
            return removed;
        }
    }

    /**
     * Replaces Spring's generic {@code /oauth2/authorization/{id}} entry point. Returning null makes
     * that endpoint inert: Google sign-in can only start from a valid desktop transaction.
     */
    public static final class GenericEntryPointDisabled implements OAuth2AuthorizationRequestResolver {
        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            return null;
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
            return null;
        }
    }
}
