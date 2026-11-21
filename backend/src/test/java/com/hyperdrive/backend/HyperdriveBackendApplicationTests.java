package com.hyperdrive.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Starts the full application on a real port with no PostgreSQL or Redis available, proving the
 * backend does not depend on those optional development services in this batch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HyperdriveBackendApplicationTests {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void healthEndpointRespondsOverHttp() throws Exception {
        HttpResponse<String> response = send("/api/v1/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .isEqualTo("{\"status\":\"UP\",\"service\":\"hyperdrive-backend\"}");
    }

    @Test
    void actuatorEndpointsAreNotExposed() throws Exception {
        assertThat(send("/actuator").statusCode()).isEqualTo(404);
        assertThat(send("/actuator/env").statusCode()).isEqualTo(404);
    }

    @Test
    void errorResponsesDoNotLeakInternals() throws Exception {
        HttpResponse<String> response = send("/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("trace", "exception", "at com.");
    }

    private HttpResponse<String> send(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
