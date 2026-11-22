package com.hyperdrive.backend.user;

import com.hyperdrive.backend.auth.session.SessionPrincipal;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The current user's profile and first-time onboarding. The user id always comes from the session. */
@RestController
@RequestMapping("/api/v1/me")
@ConditionalOnAuthEnabled
public class MeController {

    /** Only a username; the server rejects unknown fields such as a client-supplied user id. */
    public record OnboardingRequest(@NotNull @Size(max = 64) String username) {
    }

    private final UserService users;

    public MeController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<UserAccount> me(@AuthenticationPrincipal SessionPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(users.find(principal.userId()).orElseThrow());
    }

    @PostMapping("/onboarding")
    public ResponseEntity<UserAccount> onboarding(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody OnboardingRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(users.completeOnboarding(principal.userId(), request.username()));
    }
}
