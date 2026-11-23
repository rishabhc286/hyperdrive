package com.hyperdrive.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyperdrive.backend.config.AuthProperties;
import com.hyperdrive.backend.config.RateLimitProperties;
import com.hyperdrive.backend.health.ReadinessController;
import com.hyperdrive.backend.support.MutableClock;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Rate limiting, correlation ids and readiness, without a Spring context. */
class WebInfrastructureTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void rateLimiterAllowsUpToTheLimitPerWindowAndKey() {
        RateLimiter limiter = new RateLimiter(clock, Duration.ofMinutes(1), 100);
        assertThat(limiter.tryAcquire("a", 2)).isTrue();
        assertThat(limiter.tryAcquire("a", 2)).isTrue();
        assertThat(limiter.tryAcquire("a", 2)).isFalse();
        assertThat(limiter.tryAcquire("b", 2)).isTrue();
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("a", 2)).isTrue();
    }

    @Test
    void rateLimiterMemoryIsBounded() {
        RateLimiter limiter = new RateLimiter(clock, Duration.ofMinutes(1), 3);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("client-" + i, 10)).isTrue();
        }
        assertThat(limiter.tryAcquire("client-new", 10)).isFalse();
        assertThat(limiter.trackedKeys()).isEqualTo(3);
        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.tryAcquire("client-new", 10)).isTrue();
    }

    @Test
    void rateLimitFilterReturns429WithRetryAfterForSensitiveEndpoints() throws Exception {
        RateLimitProperties props = new RateLimitProperties(true, Duration.ofMinutes(1), 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 100);
        RateLimitFilter filter = new RateLimitFilter(new RateLimiter(clock, props.window(), 100), props);

        int[] statuses = new int[3];
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/desktop/transactions"), response,
                    new MockFilterChain());
            statuses[i] = response.getStatus();
            if (i == 2) {
                assertThat(response.getHeader("Retry-After")).isEqualTo("60");
                assertThat(response.getContentAsString()).contains("rate_limited");
            }
        }
        assertThat(statuses).containsExactly(200, 200, 429);

        // Unlisted endpoints are not limited.
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/health"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void storageConnectionStartPollingAndBrowserLegAreRateLimited() throws Exception {
        RateLimitProperties props = new RateLimitProperties(true, Duration.ofMinutes(1), 9, 9, 9, 9, 9, 1, 1, 2, 3, 4, 5, 100);
        RateLimitFilter filter = new RateLimitFilter(new RateLimiter(clock, props.window(), 100), props);
        record Case(String method, String path, int allowed) {
        }
        for (Case c : java.util.List.of(
                new Case("POST", "/api/v1/storage-connections/google/transactions", 1),
                new Case("GET", "/api/v1/storage-connections/google/authorize/hds_x", 1),
                new Case("GET", "/api/v1/storage-connections/transactions/abc", 2),
                new Case("GET", "/api/v1/accounts/abc/files", 3),
                // Provider-token delegation has its own, tighter bucket (not the browse one).
                new Case("POST", "/api/v1/accounts/6f1c2a8e-1b2c-4d3e-8f90-123456789abc/read-credentials", 4))) {
            int ok = 0;
            for (int i = 0; i < 5; i++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest(c.method(), c.path()), response, new MockFilterChain());
                ok += response.getStatus() == 200 ? 1 : 0;
            }
            assertThat(ok).as(c.path()).isEqualTo(c.allowed());
        }
    }

    @Test
    void correlationIdIsGeneratedOrAcceptedWhenWellFormed() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();

        MockHttpServletResponse generated = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/"), generated, new MockFilterChain());
        assertThat(generated.getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f-]{36}");

        MockHttpServletRequest supplied = new MockHttpServletRequest("GET", "/");
        supplied.addHeader(CorrelationIdFilter.HEADER, "desktop-1234abcd");
        MockHttpServletResponse echoed = new MockHttpServletResponse();
        filter.doFilter(supplied, echoed, new MockFilterChain());
        assertThat(echoed.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("desktop-1234abcd");

        MockHttpServletRequest hostile = new MockHttpServletRequest("GET", "/");
        hostile.addHeader(CorrelationIdFilter.HEADER, "bad\r\nInjected: header");
        MockHttpServletResponse replaced = new MockHttpServletResponse();
        filter.doFilter(hostile, replaced, new MockFilterChain());
        assertThat(replaced.getHeader(CorrelationIdFilter.HEADER)).doesNotContain("Injected");
    }

    @Test
    void readinessIsNotReadyWhenAuthIsEnabledButTheDatabaseIsDown() throws SQLException {
        DataSource broken = Mockito.mock(DataSource.class);
        Mockito.when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("dataSource", broken);
        AuthProperties enabled = new AuthProperties(true, URI.create("http://127.0.0.1:18080"), Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofMinutes(15), Duration.ofDays(30));

        var response = new ReadinessController(enabled, beans.getBeanProvider(DataSource.class)).ready();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo(
                new ReadinessController.ReadinessResponse("NOT_READY", ReadinessController.Auth.ENABLED));
    }
}
