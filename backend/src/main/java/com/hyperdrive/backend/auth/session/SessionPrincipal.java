package com.hyperdrive.backend.auth.session;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/**
 * The authenticated HyperDrive user behind a bearer access token. Identity always comes from the
 * server-side session row, never from client-supplied ids.
 */
public record SessionPrincipal(UUID userId, UUID sessionId, boolean onboarded) implements OAuth2AuthenticatedPrincipal {

    /** Granted to every valid session. */
    public static final String AUTHORITY_USER = "USER";
    /** Granted only after the username has been chosen; required by all non-onboarding APIs. */
    public static final String AUTHORITY_ONBOARDED = "ONBOARDED";

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("sub", userId.toString());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return onboarded
                ? List.of(new SimpleGrantedAuthority(AUTHORITY_USER), new SimpleGrantedAuthority(AUTHORITY_ONBOARDED))
                : List.of(new SimpleGrantedAuthority(AUTHORITY_USER));
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
