package io.github.joyen09.exchangecore.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joyen09.exchangecore.ledger.AccountType;
import io.github.joyen09.exchangecore.ledger.LedgerService;
import io.github.joyen09.exchangecore.ledger.PostingLine;
import io.github.joyen09.exchangecore.support.Containers;
import io.github.joyen09.exchangecore.support.SpringIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

/**
 * Both idempotency layers under genuine contention (SPEC §7.2).
 *
 * <p>The sequential tests in {@link OrderApiIT} show that a second request is recognised. They cannot
 * show that a <em>simultaneous</em> one is, because nothing in them ever interleaves: a check-then-insert
 * with a race in it passes every one of them. These two tests put 50 requests in flight at once against
 * the real servlet container, and assert on the database afterwards rather than on the responses alone —
 * the responses say what each caller was told, the row counts say what actually happened.
 *
 * <p>50 threads is not a load test. It is simply more concurrency than the single connection pool and the
 * unique indexes have been asked to survive so far, which is the point.
 */
class OrderConcurrencyIT extends SpringIntegrationTest {

    private static final int CALLERS = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Containers.reset();
        UUID external = ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
        UUID available = ledger.getOrCreateAccount("local", "USDT", AccountType.AVAILABLE).id();
        // Funded far beyond one order, deliberately: if the duplicate suppression leaked, the extra orders
        // would succeed rather than being stopped by a balance check, so the count is the only witness.
        ledger.post(
                "fund-" + UUID.randomUUID(),
                "DEPOSIT",
                null,
                List.of(
                        PostingLine.of(external, new BigDecimal("-100000000")),
                        PostingLine.of(available, new BigDecimal("100000000"))));
    }

    @Test
    @DisplayName("50 simultaneous requests with one Idempotency-Key produce exactly one order")
    void oneKeyFiftyCallers() throws Exception {
        String key = "key-" + UUID.randomUUID();
        String body = body("concurrent-key", "0.5", "60000");

        List<ResponseEntity<String>> responses = fireTogether(() -> post(key, body));

        long created = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                .count();
        long conflicts = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .count();

        // Every caller is told something defensible, and nothing else occurs. A 201 is either the winner
        // or a replay of the winner's recorded response; a 409 is "your key is mid-flight, ask again".
        assertThat(created + conflicts)
                .as("responses that were neither a 201 nor a 409: %s", unexpected(responses))
                .isEqualTo(CALLERS);
        assertThat(created).as("at least the winner answers 201").isGreaterThanOrEqualTo(1);
        responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(r -> assertThat(code(r)).isEqualTo("REQUEST_IN_PROGRESS"));

        // The claim is committed before the work starts, which is what makes 409 observable at all. The
        // alternative — one long transaction — would make the losers block and then all answer 201, and
        // the caller would never learn that its retry had arrived too early.
        assertThat(conflicts)
                .as("with 50 callers at once, some arrive while the claim is held")
                .isGreaterThanOrEqualTo(1);

        // Every 201 carries the same body, because they are all the same recorded response.
        List<String> bodies = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                .map(ResponseEntity::getBody)
                .distinct()
                .toList();
        assertThat(bodies).as("a replay is the original response, not a second one").hasSize(1);

        assertOneOrderOneEventOneMessage();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("50 distinct keys with one clientOrderId still produce exactly one order")
    void fiftyKeysOneClientOrderId() throws Exception {
        String body = body("concurrent-client-id", "0.5", "60000");

        // Distinct keys, so the HTTP layer waves all 50 through: the only thing left to stop them is the
        // domain-level unique index on (owner_id, client_order_id). This is the test that would fail if
        // that constraint were replaced with a SELECT-then-INSERT.
        List<ResponseEntity<String>> responses = fireTogether(() -> post("key-" + UUID.randomUUID(), body));

        assertThat(responses)
                .as("the domain duplicate is not an error — the caller gets the order that exists")
                .allMatch(r -> r.getStatusCode() == HttpStatus.CREATED || r.getStatusCode() == HttpStatus.OK);

        List<String> ids = responses.stream()
                .map(r -> parse(r).get("id").asText())
                .distinct()
                .toList();
        assertThat(ids).as("all 50 callers were given the same order").hasSize(1);

        long creations = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                .count();
        assertThat(creations).as("exactly one caller created it").isEqualTo(1);

        // The other 49 are told plainly that they did not create anything, rather than being handed a 201
        // that is not true.
        responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK)
                .forEach(r -> assertThat(parse(r).get("notice").get("code").asText())
                        .isEqualTo("DUPLICATE_CLIENT_ORDER_ID"));

        assertOneOrderOneEventOneMessage();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long.class))
                .as("each caller still gets its own key row — they were different requests")
                .isEqualTo((long) CALLERS);
    }

    /**
     * What §7.2 actually asks for: one order, one event, one message. Checked on the tables, because a
     * duplicate that was suppressed in the response but not in the outbox would be invisible otherwise —
     * and would reach the consumer as a second ORDER_CREATED.
     */
    private void assertOneOrderOneEventOneMessage() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class))
                .as("orders")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_events", Long.class))
                .as("order_events")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class))
                .as("outbox")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT max(sequence_no) FROM order_events", Long.class))
                .as("the one event is the first one")
                .isEqualTo(1L);
    }

    private List<ResponseEntity<String>> fireTogether(Callable<ResponseEntity<String>> request) throws Exception {
        CountDownLatch ready = new CountDownLatch(CALLERS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(CALLERS)) {
            for (int i = 0; i < CALLERS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    // Released all at once rather than as each thread is scheduled, so the requests
                    // genuinely overlap instead of queueing behind the pool's ramp-up.
                    if (!go.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                    return request.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all callers reached the gate").isTrue();
            go.countDown();
        }

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        return responses;
    }

    private ResponseEntity<String> post(String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String body(String clientOrderId, String quantity, String price) {
        return """
                {"clientOrderId":"%s","symbol":"BTCUSDT","side":"BUY","type":"LIMIT","timeInForce":"GTC",
                 "quantity":"%s","price":"%s"}"""
                .formatted(clientOrderId, quantity, price);
    }

    private static String unexpected(List<ResponseEntity<String>> responses) {
        return responses.stream()
                .filter(r -> r.getStatusCode() != HttpStatus.CREATED && r.getStatusCode() != HttpStatus.CONFLICT)
                .map(r -> r.getStatusCode() + " " + r.getBody())
                .distinct()
                .toList()
                .toString();
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
}
