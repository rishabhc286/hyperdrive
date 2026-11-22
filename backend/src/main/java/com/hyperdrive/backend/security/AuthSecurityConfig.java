package com.hyperdrive.backend.security;

import com.hyperdrive.backend.auth.desktop.DesktopLoginHandlers;
import com.hyperdrive.backend.auth.desktop.TransactionBoundAuthorizationRequests;
import com.hyperdrive.backend.auth.session.SessionPrincipal;
import com.hyperdrive.backend.auth.session.SessionService;
import com.hyperdrive.backend.config.ConditionalOnAuthEnabled;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * Auth-enabled security. Three separate filter chains:
 *
 * <ol>
 *   <li><b>Browser chain</b> — the authorize entry point and Google's callback. Uses an HTTP session
 *       only to hold Spring's OAuth authorization request; the resulting authentication is never
 *       stored in the session (request-scoped security context) and the session is invalidated by
 *       the success/failure handler. CSRF protection stays on (only GET endpoints exist here).</li>
 *   <li><b>API chain</b> — {@code /api/**}. Stateless, bearer tokens only. No session or cookie is
 *       read, so a browser holding a temporary OAuth cookie gains nothing here. CSRF is disabled
 *       deliberately: CSRF exploits ambient (cookie) credentials, and this chain accepts none.</li>
 *   <li><b>Fallback chain</b> — everything else is denied.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuthEnabled
public class AuthSecurityConfig {

    @Bean
    TransactionBoundAuthorizationRequests.Repository authorizationRequestRepository() {
        return new TransactionBoundAuthorizationRequests.Repository();
    }

    @Bean
    @Order(1)
    SecurityFilterChain browserSignIn(HttpSecurity http, DesktopLoginHandlers handlers,
            TransactionBoundAuthorizationRequests.Repository authorizationRequests) throws Exception {
        http
                .securityMatcher("/api/v1/auth/desktop/authorize/**", "/login/oauth2/code/**", "/oauth2/authorization/**")
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(a -> a
                                .authorizationRequestResolver(new TransactionBoundAuthorizationRequests.GenericEntryPointDisabled())
                                .authorizationRequestRepository(authorizationRequests))
                        .successHandler(handlers)
                        .failureHandler(handlers))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/desktop/authorize/*").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(ProblemSecurityHandlers.forbiddenEntryPoint())
                        .accessDeniedHandler(ProblemSecurityHandlers.forbidden()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain bearerApi(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        http
                .securityMatcher("/api/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .oauth2ResourceServer(rs -> rs
                        .opaqueToken(t -> t.introspector(introspector))
                        .authenticationEntryPoint(ProblemSecurityHandlers.bearerEntryPoint())
                        .accessDeniedHandler(ProblemSecurityHandlers.bearerAccessDenied()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/ready").permitAll()
                        // Browser leg of a storage connection. Authorized by a one-time ticket, then by
                        // OAuth state + a browser-binding cookie; never by a HyperDrive bearer token.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/storage-connections/google/authorize/*",
                                "/api/v1/storage-connections/google/callback",
                                "/api/v1/storage-connections/dropbox/authorize/*",
                                "/api/v1/storage-connections/dropbox/callback").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/desktop/transactions",
                                "/api/v1/auth/desktop/exchange",
                                "/api/v1/auth/refresh").permitAll()
                        // Reachable before onboarding: profile, onboarding and logout only.
                        .requestMatchers(HttpMethod.GET, "/api/v1/me").hasAuthority(SessionPrincipal.AUTHORITY_USER)
                        .requestMatchers(HttpMethod.POST, "/api/v1/me/onboarding", "/api/v1/auth/logout")
                        .hasAuthority(SessionPrincipal.AUTHORITY_USER)
                        // Every other API requires a completed username.
                        .anyRequest().hasAuthority(SessionPrincipal.AUTHORITY_ONBOARDED))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(ProblemSecurityHandlers.bearerEntryPoint())
                        .accessDeniedHandler(ProblemSecurityHandlers.bearerAccessDenied()));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain denyEverythingElse(HttpSecurity http) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(ProblemSecurityHandlers.forbiddenEntryPoint())
                        .accessDeniedHandler(ProblemSecurityHandlers.forbidden()));
        return http.build();
    }

    /** Resolves HyperDrive opaque tokens locally against the database (no remote introspection). */
    @Bean
    OpaqueTokenIntrospector hyperdriveTokenIntrospector(SessionService sessions) {
        return token -> sessions.authenticate(token)
                .orElseThrow(() -> new BadOpaqueTokenException("Invalid access token"));
    }
}
