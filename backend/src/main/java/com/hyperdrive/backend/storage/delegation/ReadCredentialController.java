package com.hyperdrive.backend.storage.delegation;

import com.hyperdrive.backend.auth.session.SessionPrincipal;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/accounts/{accountId}/read-credentials}: bearer-authenticated, onboarded
 * sessions only (enforced by the API security chain), no request body, rate limited, no-store.
 */
@RestController
@ConditionalOnAuthEnabled
public class ReadCredentialController {

    private final ReadCredentialService service;

    public ReadCredentialController(ReadCredentialService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/accounts/{accountId}/read-credentials")
    public ResponseEntity<ReadCredentialService.ReadCredential> issue(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID accountId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(service.issue(principal.userId(), principal.sessionId(), accountId));
    }
}
