package com.hyperdrive.backend.vfs;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/**
 * The holder of a metadata grant. Carries only {@link #AUTHORITY_METADATA_READ}: it never has the
 * USER or ONBOARDED authorities, so every other API (credential delegation, account administration,
 * connections, profile) rejects it with 403.
 */
public record MetadataPrincipal(UUID userId, UUID sessionId) implements OAuth2AuthenticatedPrincipal {

    public static final String AUTHORITY_METADATA_READ = "METADATA_READ";

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("sub", userId.toString());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(AUTHORITY_METADATA_READ));
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
