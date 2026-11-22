package com.hyperdrive.backend.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Health-only mode (no {@code auth} profile): exactly the Batch 1 surface — liveness and readiness.
 * Everything else is denied. Nothing simulates a login.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "hyperdrive.auth.enabled", havingValue = false, matchIfMissing = true)
public class AuthDisabledSecurityConfig {

    @Bean
    SecurityFilterChain healthOnly(HttpSecurity http) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No state-changing endpoint exists in this mode and no cookie is ever issued.
                .csrf(csrf -> csrf.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/ready").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(ProblemSecurityHandlers.forbiddenEntryPoint())
                        .accessDeniedHandler(ProblemSecurityHandlers.forbidden()));
        return http.build();
    }
}
