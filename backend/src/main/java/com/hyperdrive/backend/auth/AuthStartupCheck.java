package com.hyperdrive.backend.auth;

import com.hyperdrive.backend.auth.desktop.DesktopBrowserController;
import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import java.net.URI;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Fails startup with an actionable message when auth is enabled but misconfigured, instead of
 * starting in a half-working or silently bypassed state.
 */
@Component
@ConditionalOnAuthEnabled
public class AuthStartupCheck implements InitializingBean {

    private final AuthProperties properties;
    private final ClientRegistrationRepository registrations;

    public AuthStartupCheck(AuthProperties properties, ClientRegistrationRepository registrations) {
        this.properties = properties;
        this.registrations = registrations;
    }

    @Override
    public void afterPropertiesSet() {
        URI base = properties.publicBaseUrl();
        if (base == null || base.getHost() == null
                || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || (base.getPath() != null && !base.getPath().isEmpty() && !"/".equals(base.getPath()))
                || base.getQuery() != null || base.getUserInfo() != null) {
            throw new IllegalStateException(
                    "HYPERDRIVE_PUBLIC_BASE_URL must be an http(s) origin such as http://127.0.0.1:18080");
        }
        ClientRegistration google = registrations.findByRegistrationId(DesktopBrowserController.GOOGLE);
        if (google == null || missing(google.getClientId()) || missing(google.getClientSecret())) {
            throw new IllegalStateException("Google sign-in is not configured: set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET");
        }
        String expectedRedirect = base.toString().replaceAll("/$", "") + "/login/oauth2/code/google";
        if (!expectedRedirect.equals(google.getRedirectUri())) {
            throw new IllegalStateException("Google redirect URI must be " + expectedRedirect);
        }
        if (google.getScopes().stream().anyMatch(s -> !s.equals("openid") && !s.equals("profile") && !s.equals("email"))) {
            throw new IllegalStateException("Sign-in may request only openid, profile and email scopes");
        }
    }

    /** Blank, or an unresolved ${...} placeholder that Spring left as literal text. */
    private static boolean missing(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }
}
