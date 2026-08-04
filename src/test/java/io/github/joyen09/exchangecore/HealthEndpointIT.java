package io.github.joyen09.exchangecore;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joyen09.exchangecore.config.ExchangeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the whole application against a real PostgreSQL (SPEC §5.5: no mocked database) and checks
 * the Phase 0 contract: the service starts, Flyway migrates, {@code /health} answers, metrics are
 * exposed, and nothing else is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability // Spring Boot switches metrics export off inside tests; the scrape endpoint is the thing under test here.
@Testcontainers
class HealthEndpointIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("exchange_core_lab")
            .withUsername("exchange_core")
            .withPassword("local_dev_only");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExchangeProperties exchangeProperties;

    @Test
    @DisplayName("/health reports UP with the database component healthy")
    void healthEndpointReportsUp() {
        ResponseEntity<String> response = rest.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"").contains("\"db\"");
    }

    @Test
    @DisplayName("Flyway applied the baseline migration")
    void baselineMigrationApplied() {
        String serviceName = jdbc.queryForObject(
                "SELECT meta_value FROM service_metadata WHERE meta_key = ?", String.class, "service_name");
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);

        assertThat(serviceName).isEqualTo("exchange-core-lab");
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("/prometheus exposes JVM metrics for the Phase 5 dashboards")
    void prometheusEndpointExposesMetrics() {
        ResponseEntity<String> response = rest.getForEntity("/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("unexposed actuator endpoints stay unreachable")
    void onlyThreeEndpointsAreExposed() {
        assertThat(rest.getForEntity("/env", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/beans", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/heapdump", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the running service is bound to the Binance Spot Testnet")
    void exchangeEndpointsAreTestnet() {
        assertThat(exchangeProperties.getRestBaseUrl()).isEqualTo("https://testnet.binance.vision");
        assertThat(exchangeProperties.getWsBaseUrl()).isEqualTo("wss://stream.testnet.binance.vision:9443");
        assertThat(exchangeProperties.getConnectTimeout()).isNotNull();
        assertThat(exchangeProperties.getReadTimeout()).isNotNull();
    }
}
