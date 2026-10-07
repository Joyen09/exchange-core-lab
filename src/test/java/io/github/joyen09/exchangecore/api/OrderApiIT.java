package io.github.joyen09.exchangecore.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joyen09.exchangecore.ledger.AccountType;
import io.github.joyen09.exchangecore.ledger.LedgerService;
import io.github.joyen09.exchangecore.ledger.PostingLine;
import io.github.joyen09.exchangecore.order.OrderService;
import io.github.joyen09.exchangecore.support.Containers;
import io.github.joyen09.exchangecore.support.SpringIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The HTTP contract from SPEC §6, against the running application. */
class OrderApiIT extends SpringIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private OrderService orders;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Containers.reset();
        UUID external = ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
        UUID available = ledger.getOrCreateAccount("local", "USDT", AccountType.AVAILABLE).id();
        ledger.post(
                "fund-" + UUID.randomUUID(),
                "DEPOSIT",
                null,
                List.of(
                        PostingLine.of(external, new BigDecimal("-1000000")),
                        PostingLine.of(available, new BigDecimal("1000000"))));
    }

    @Test
    @DisplayName("placing an order returns 201, a Location, and amounts as strings")
    void placeReturnsCreated() {
        ResponseEntity<String> response = post(key(), body("api-1", "0.5", "60000"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = parse(response);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/v1/orders/" + body.get("id").asText());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("quantity").isTextual())
                .as("amounts must be strings; a JSON number invites the client to use a double")
                .isTrue();
        assertThat(new BigDecimal(body.get("quantity").asText())).isEqualByComparingTo("0.5");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    @DisplayName("the same key and the same body replays the original response byte for byte")
    void replayIsIdentical() {
        String key = key();
        String body = body("api-replay", "0.5", "60000");

        ResponseEntity<String> first = post(key, body);
        ResponseEntity<String> second = post(key, body);

        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different body is 422, and leaves the first result intact")
    void reusedKeyWithDifferentBodyIsRefused() {
        String key = key();
        ResponseEntity<String> first = post(key, body("api-reuse", "0.5", "60000"));

        ResponseEntity<String> second = post(key, body("api-reuse-other", "0.9", "60000"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(second)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(countOrders()).isEqualTo(1);
        assertThat(parse(first).get("clientOrderId").asText()).isEqualTo("api-reuse");
    }

    @Test
    @DisplayName("the same clientOrderId under a different key returns the existing order with a notice")
    void duplicateClientOrderIdReturnsTheExistingOrder() {
        ResponseEntity<String> first = post(key(), body("api-dup", "0.5", "60000"));

        ResponseEntity<String> second = post(key(), body("api-dup", "0.5", "60000"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(second).get("id").asText()).isEqualTo(parse(first).get("id").asText());
        assertThat(parse(second).get("notice").get("code").asText()).isEqualTo("DUPLICATE_CLIENT_ORDER_ID");
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing Idempotency-Key is 400")
    void keyIsRequired() {
        ResponseEntity<String> response = http.postForEntity("/api/v1/orders", json(body("api-nokey", "0.5", "60000")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(response)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName("a market order is refused, because its lock cannot be computed")
    void marketOrdersAreRefused() {
        ResponseEntity<String> response = post(
                key(),
                """
                {"clientOrderId":"api-market","symbol":"BTCUSDT","side":"BUY","type":"MARKET","quantity":"0.5"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(response)).isEqualTo("MARKET_ORDER_NOT_SUPPORTED_YET");
    }

    @Test
    @DisplayName("an unknown symbol is refused rather than guessed at")
    void unknownSymbolIsRefused() {
        ResponseEntity<String> response = post(
                key(),
                """
                {"clientOrderId":"api-unknown","symbol":"DOGEUSDT","side":"BUY","type":"LIMIT",
                 "timeInForce":"GTC","quantity":"1","price":"1"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(response)).isEqualTo("UNKNOWN_SYMBOL");
    }

    @Test
    @DisplayName("field errors are listed one by one")
    void validationListsEveryField() {
        ResponseEntity<String> response = post(key(), "{\"side\":\"SIDEWAYS\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
        JsonNode errors = parse(response).get("errors");
        assertThat(errors.isArray()).isTrue();
        assertThat(errors.toString()).contains("clientOrderId").contains("symbol").contains("side");
    }

    @Test
    @DisplayName("not enough money is still a 201 — the request succeeded, the order did not")
    void insufficientFundsIsCreatedAndRejected() {
        ResponseEntity<String> response = post(key(), body("api-poor", "100", "60000"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(parse(response).get("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("fetching an order, and failing to")
    void getOrder() {
        String id = parse(post(key(), body("api-get", "0.5", "60000"))).get("id").asText();

        assertThat(http.getForEntity("/api/v1/orders/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> missing = http.getForEntity("/api/v1/orders/" + UUID.randomUUID(), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(missing)).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("cancelling a pending order completes immediately; cancelling a live one is accepted")
    void cancellation() {
        UUID pending = UUID.fromString(parse(post(key(), body("api-cancel-1", "0.5", "60000"))).get("id").asText());

        ResponseEntity<String> immediate = http.exchange("/api/v1/orders/" + pending, HttpMethod.DELETE, null, String.class);
        assertThat(immediate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(immediate).get("status").asText()).isEqualTo("CANCELED");

        UUID live = UUID.fromString(parse(post(key(), body("api-cancel-2", "0.5", "60000"))).get("id").asText());
        orders.submit(live);

        ResponseEntity<String> accepted = http.exchange("/api/v1/orders/" + live, HttpMethod.DELETE, null, String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(parse(accepted).get("status").asText()).isEqualTo("CANCELING");

        long events = countEvents(live);
        ResponseEntity<String> again = http.exchange("/api/v1/orders/" + live, HttpMethod.DELETE, null, String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(countEvents(live)).as("a repeated cancel request emits no event").isEqualTo(events);

        ResponseEntity<String> terminal =
                http.exchange("/api/v1/orders/" + pending, HttpMethod.DELETE, null, String.class);
        assertThat(terminal.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(terminal)).isEqualTo("ORDER_ALREADY_TERMINAL");
    }

    @Test
    @DisplayName("keyset pagination walks every order exactly once")
    void keysetPagination() {
        for (int i = 0; i < 5; i++) {
            post(key(), body("api-page-" + i, "0.5", "60000"));
        }

        List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String url = "/api/v1/orders?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode body = parse(http.getForEntity(url, String.class));
            body.get("items").forEach(item -> seen.add(item.get("id").asText()));
            if (body.get("nextCursor").isNull()) {
                break;
            }
            cursor = body.get("nextCursor").asText();
        }

        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a cursor that did not come from us is refused")
    void badCursorIsRefused() {
        ResponseEntity<String> response = http.getForEntity("/api/v1/orders?cursor=not-a-cursor", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(response)).isEqualTo("INVALID_CURSOR");
    }

    @Test
    @DisplayName("Spring's own errors keep their status instead of becoming 500")
    void frameworkErrorsAreNotSwallowedByTheCatchAll() {
        // A @RestControllerAdvice with an @ExceptionHandler(Exception.class) applies to the whole
        // application, so the catch-all originally caught these too and answered 500 — telling the caller
        // the server was broken when the caller had simply used the wrong URL. Caught by HealthEndpointIT
        // asserting that an unexposed actuator endpoint is 404; kept here, next to the handler it is
        // about, with the path-variable case the other test could not have found.
        assertThat(http.getForEntity("/api/v1/no-such-thing", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/v1/orders/not-a-uuid", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> wrongMethod =
                http.exchange("/api/v1/orders/" + UUID.randomUUID(), HttpMethod.PUT, json("{}"), String.class);
        assertThat(wrongMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        // And they still carry the machine-readable code every other error has.
        assertThat(code(http.getForEntity("/api/v1/orders/not-a-uuid", String.class))).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("a limit above the maximum is refused rather than silently clamped")
    void limitIsBounded() {
        ResponseEntity<String> response = http.getForEntity("/api/v1/orders?limit=500", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("the event reaches Kafka and the verifying consumer, end to end")
    void eventsReachTheConsumer() {
        String id = parse(post(key(), body("api-kafka", "0.5", "60000"))).get("id").asText();

        // The scheduled publisher and the real broker, with no test seam in between: this is the only
        // assertion in the suite that the wiring actually works.
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM processed_events WHERE aggregate_id = ?",
                                Long.class,
                                UUID.fromString(id)))
                        .isEqualTo(1L));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND published_at IS NOT NULL",
                        Long.class,
                        UUID.fromString(id)))
                .isEqualTo(1L);
    }

    private ResponseEntity<String> post(String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String body(String clientOrderId, String quantity, String price) {
        return """
                {"clientOrderId":"%s","symbol":"BTCUSDT","side":"BUY","type":"LIMIT","timeInForce":"GTC",
                 "quantity":"%s","price":"%s"}"""
                .formatted(clientOrderId, quantity, price);
    }

    private static JsonNode parse(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("response body is not JSON: " + response.getBody(), e);
        }
    }

    private static String code(ResponseEntity<String> response) {
        return parse(response).get("code").asText();
    }

    private long countOrders() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    private long countEvents(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM order_events WHERE order_id = ?", Long.class, orderId);
    }
}
