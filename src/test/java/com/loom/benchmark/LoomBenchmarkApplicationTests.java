package com.loom.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.loom.benchmark.model.AggregatedResponse;

/**
 * Integration tests that start the full Spring Boot context and hit the real endpoints.
 *
 * We use a small source count (3) to keep tests fast while still exercising
 * the full fan-out → aggregate → response cycle for both executors.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LoomBenchmarkApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // Verifies the Spring context (beans, config, executor wiring) starts without errors.
    }

    @Test
    void classicEndpointReturnsAggregatedResponse() {
        ResponseEntity<AggregatedResponse> response =
                restTemplate.getForEntity("/api/aggregate/classic?sources=3", AggregatedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        AggregatedResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.mode()).isEqualTo("CLASSIC");
        assertThat(body.sourcesRequested()).isEqualTo(3);
        assertThat(body.sourcesCompleted()).isEqualTo(3);
        assertThat(body.results()).hasSize(3);
        assertThat(body.totalDurationMs()).isGreaterThan(0);
        assertThat(body.threadPoolSize()).isEqualTo(20); // from application.yml default

        // Verify thread names contain "platform" annotation
        body.results().forEach(r ->
                assertThat(r.threadName()).contains("[platform]"));
    }

    @Test
    void loomEndpointReturnsAggregatedResponse() {
        ResponseEntity<AggregatedResponse> response =
                restTemplate.getForEntity("/api/aggregate/loom?sources=3", AggregatedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        AggregatedResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.mode()).isEqualTo("LOOM");
        assertThat(body.sourcesRequested()).isEqualTo(3);
        assertThat(body.sourcesCompleted()).isEqualTo(3);
        assertThat(body.results()).hasSize(3);
        assertThat(body.threadPoolSize()).isEqualTo(-1); // virtual threads = no fixed pool

        // Verify thread names contain "virtual" annotation
        body.results().forEach(r ->
                assertThat(r.threadName()).contains("[virtual]"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compareEndpointReturnsBothModes() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity("/api/aggregate/compare?sources=3", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("classic", "loom");
    }

    @Test
    void defaultSourceCountIsAppliedWhenParamOmitted() {
        // When ?sources is omitted, the default value of 20 should be used.
        ResponseEntity<AggregatedResponse> response =
                restTemplate.getForEntity("/api/aggregate/loom", AggregatedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sourcesRequested()).isEqualTo(20);
    }
}
