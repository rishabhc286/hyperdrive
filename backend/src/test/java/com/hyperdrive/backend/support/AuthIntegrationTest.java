package com.hyperdrive.backend.support;

import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for tests that need the auth profile: a real PostgreSQL 18 (Testcontainers, same major as
 * compose.yaml), Flyway migrations, and a fake OIDC provider standing in for Google.
 * Skipped (not failed) when Docker is unavailable; the build output then shows them as skipped.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth")
@Import(AuthIntegrationTest.TestClockConfig.class)
public abstract class AuthIntegrationTest {

    public static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";
    /** MockMvc requests default to http://localhost; the redirect URI must match it exactly. */
    public static final String PUBLIC_BASE_URL = "http://localhost";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    protected static FakeOidcProvider google;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        if (google == null) {
            google = new FakeOidcProvider(CLIENT_ID);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("GOOGLE_CLIENT_ID", () -> CLIENT_ID);
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add("HYPERDRIVE_PUBLIC_BASE_URL", () -> PUBLIC_BASE_URL);
        // Point the "google" registration at the fake provider via standard OIDC discovery.
        registry.add("spring.security.oauth2.client.provider.google.issuer-uri", google::issuer);
        registry.add("hyperdrive.rate-limit.enabled", () -> "false");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected Clock clock;

    protected MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE users, login_transactions CASCADE").update();
        mutableClock().reset();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
