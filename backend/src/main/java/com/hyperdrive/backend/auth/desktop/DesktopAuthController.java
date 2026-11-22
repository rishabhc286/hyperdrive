package com.hyperdrive.backend.auth.desktop;

import com.hyperdrive.backend.auth.desktop.DesktopAuthRequests.CreateTransaction;
import com.hyperdrive.backend.auth.desktop.DesktopAuthRequests.Exchange;
import com.hyperdrive.backend.auth.desktop.DesktopAuthRequests.Refresh;
import com.hyperdrive.backend.auth.desktop.DesktopAuthRequests.TransactionCreated;
import com.hyperdrive.backend.auth.session.SessionPrincipal;
import com.hyperdrive.backend.auth.session.SessionService;
import com.hyperdrive.backend.auth.session.TokenResponse;
import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import com.hyperdrive.backend.user.UserService;
import com.hyperdrive.backend.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Bearer/JSON API used by the desktop main process. Tokens are returned only by exchange and refresh. */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnAuthEnabled
public class DesktopAuthController {

    private final LoginTransactionService transactions;
    private final SessionService sessions;
    private final UserService users;
    private final AuthProperties properties;

    public DesktopAuthController(LoginTransactionService transactions, SessionService sessions, UserService users,
            AuthProperties properties) {
        this.transactions = transactions;
        this.sessions = sessions;
        this.users = users;
        this.properties = properties;
    }

    @PostMapping("/desktop/transactions")
    public ResponseEntity<TransactionCreated> createTransaction(@Valid @RequestBody CreateTransaction request) {
        LoginTransactionService.Created created = transactions.create(
                request.codeChallenge(), request.callbackPort(), request.callbackState(), request.deviceName());
        String authorizationUrl = UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .path(DesktopBrowserController.AUTHORIZE_PATH).path("/{id}")
                .buildAndExpand(created.id()).toUriString();
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new TransactionCreated(created.id(), authorizationUrl, created.expiresAt()));
    }

    @PostMapping("/desktop/exchange")
    public ResponseEntity<TokenResponse> exchange(@Valid @RequestBody Exchange request) {
        LoginTransactionService.Consumed consumed = transactions
                .consume(request.transactionId(), request.code(), request.codeVerifier())
                .orElseThrow(ApiException::invalidGrant);
        TokenResponse tokens = sessions.createSession(consumed.userId(), consumed.deviceName());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(tokens.withUser(users.find(consumed.userId()).orElseThrow()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody Refresh request) {
        SessionService.RefreshResult result = sessions.refresh(request.refreshToken());
        if (result.outcome() != SessionService.RefreshOutcome.ROTATED) {
            // Same response for unknown, expired, revoked and replayed tokens.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
                    "Your session has expired or was signed out. Sign in again.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.tokens());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal SessionPrincipal principal) {
        sessions.logout(principal.sessionId());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
