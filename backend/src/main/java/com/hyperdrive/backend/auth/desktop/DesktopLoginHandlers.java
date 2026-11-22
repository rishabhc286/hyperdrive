package com.hyperdrive.backend.auth.desktop;

import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.user.ExternalIdentity;
import com.hyperdrive.backend.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Runs after Spring Security has fully validated Google's response (OAuth state, code exchange, ID
 * token signature/issuer/audience/expiry/nonce). Converts the result into a one-time handoff to the
 * desktop and then discards all browser-side authentication state.
 */
@Component
@ConditionalOnAuthEnabled
public class DesktopLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(DesktopLoginHandlers.class);

    private final LoginTransactionService transactions;
    private final UserService users;

    public DesktopLoginHandlers(LoginTransactionService transactions, UserService users) {
        this.transactions = transactions;
        this.users = users;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        UUID transactionId = TransactionBoundAuthorizationRequests.transactionOf(request);
        discardBrowserSession(request);

        if (transactionId == null || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            BrowserPages.invalidLink(response);
            return;
        }
        ExternalIdentity identity = new ExternalIdentity(
                oidcUser.getIdToken().getIssuer().toString(),
                oidcUser.getSubject(),
                oidcUser.getFullName() != null ? oidcUser.getFullName() : oidcUser.getGivenName(),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()) ? oidcUser.getEmail() : null);
        UUID userId = users.findOrCreate(identity);

        Optional<LoginTransactionService.HandoffIssued> handoff = transactions.completeBrowserLogin(transactionId, userId);
        if (handoff.isEmpty()) {
            BrowserPages.invalidLink(response);
            return;
        }
        BrowserPages.redirect(response, handoff.get().callback().withParams("code", handoff.get().code()));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        UUID transactionId = TransactionBoundAuthorizationRequests.transactionOf(request);
        discardBrowserSession(request);
        String reason = exception instanceof OAuth2AuthenticationException oauth
                && "access_denied".equals(oauth.getError().getErrorCode()) ? "access_denied" : "login_failed";
        log.info("Google sign-in did not complete: {}", exception instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode() : exception.getClass().getSimpleName());

        Optional<LoginTransactionService.Callback> callback =
                transactionId == null ? Optional.empty() : transactions.fail(transactionId);
        if (callback.isPresent()) {
            BrowserPages.redirect(response, callback.get().withParams("error", reason));
            return;
        }
        BrowserPages.message(response, HttpServletResponse.SC_BAD_REQUEST, "Sign-in didn't complete",
                "Return to HyperDrive and choose “Continue with Google” again.");
    }

    /** The browser never keeps a HyperDrive login; each desktop sign-in needs its own Google round trip. */
    private static void discardBrowserSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
