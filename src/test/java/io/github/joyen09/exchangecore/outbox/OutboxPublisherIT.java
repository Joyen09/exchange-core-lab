package io.github.joyen09.exchangecore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joyen09.exchangecore.ledger.AccountType;
import io.github.joyen09.exchangecore.ledger.PostingLine;
import io.github.joyen09.exchangecore.order.Fill;
import io.github.joyen09.exchangecore.order.Order;
import io.github.joyen09.exchangecore.order.OrderSide;
import io.github.joyen09.exchangecore.order.OrderType;
import io.github.joyen09.exchangecore.order.PlaceOrderCommand;
import io.github.joyen09.exchangecore.order.TimeInForce;
import io.github.joyen09.exchangecore.support.Containers;
import io.github.joyen09.exchangecore.support.OrderTestStack;
import io.github.joyen09.exchangecore.support.RecordingEventSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The outbox's failure behaviour (SPEC §7.3) — the part that cannot be observed on a good day. */
class OutboxPublisherIT {

    private OrderTestStack stack;

    @BeforeEach
    void setUp() {
        Containers.reset();
        stack = new OrderTestStack();
        fund(new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("a publisher that dies after sending but before marking causes a resend, and one effect")
    void deathBetweenSendAndMarkIsSurvivable() {
        UUID orderId = place("interrupted", new BigDecimal("1"), new BigDecimal("100")).id();
        long rows = stack.outboxRepository.findByAggregate(orderId).size();
        assertThat(rows).isEqualTo(1);

        // The crash: the broker has the message, the database does not yet know. Nothing about this is
        // exotic — it is the normal consequence of two systems and one failure point between them.
        RecordingEventSender sender = new RecordingEventSender();
        OutboxPublisher dying = publisherWith(sender, new OutboxRepository(stack.jdbc) {
            @Override
            public void markPublished(long id, Instant publishedAt) {
                throw new IllegalStateException("process died before committing");
            }
        });

        assertThatThrownBy(dying::publishOnce).isInstanceOf(IllegalStateException.class);
        assertThat(sender.sent()).as("the broker did receive it").hasSize(1);
        assertThat(stack.outboxRepository.findByAggregate(orderId).getFirst().publishedAt())
                .as("but the row is still unpublished, so it will be sent again")
                .isNull();

        // Restart: the same message goes out a second time.
        assertThat(stack.publisher.publishOnce()).isEqualTo(1);
        assertThat(stack.sender.sent()).hasSize(1);

        // Two deliveries, one effect. That is what at-least-once plus de-duplication buys.
        boolean firstApplied = stack.verifier.ingest(sender.payloads().getFirst());
        boolean secondApplied = stack.verifier.ingest(stack.sender.payloads().getFirst());

        assertThat(firstApplied).isTrue();
        assertThat(secondApplied).as("the redelivery must be suppressed").isFalse();
        assertThat(stack.processedEvents.countFor(orderId)).isEqualTo(1);
        assertThat(stack.counter("consumer_duplicates_total")).isEqualTo(1d);
    }

    @Test
    @DisplayName("100 events for one order arrive with strictly increasing sequence numbers")
    void eventsForOneOrderStayInOrder() {
        // Quantity large enough that 98 fills of one unit each leave the order still partially filled,
        // so the chain does not terminate before it is long enough to be interesting.
        Order order = place("ordered", new BigDecimal("1000"), new BigDecimal("1"));
        stack.orders.submit(order.id());
        for (int i = 0; i < 98; i++) {
            stack.orders.recordFill(order.id(), new Fill(BigDecimal.ONE, BigDecimal.ONE));
        }

        assertThat(stack.publisher.publishOnce()).isEqualTo(100);

        List<Long> sequences = new ArrayList<>();
        for (String payload : stack.sender.payloads()) {
            assertThat(stack.verifier.ingest(payload)).isTrue();
            sequences.add(sequenceOf(payload));
        }

        assertThat(sequences).hasSize(100).isSorted();
        assertThat(sequences).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 100)
                .boxed()
                .toList());
        assertThat(stack.counter("consumer_out_of_order_total")).isZero();
        assertThat(stack.sender.sent())
                .as("the partition key is the order id, which is what keeps one order's events ordered")
                .allMatch(sent -> sent.key().equals(order.id().toString()));
    }

    @Test
    @DisplayName("two publishers share the work and send nothing twice")
    void parallelPublishersDoNotDuplicate() throws Exception {
        for (int i = 0; i < 40; i++) {
            place("parallel-" + i, new BigDecimal("1"), new BigDecimal("100"));
        }
        long pending = stack.outboxRepository.backlog();
        assertThat(pending).isEqualTo(40);

        RecordingEventSender firstSender = new RecordingEventSender();
        RecordingEventSender secondSender = new RecordingEventSender();
        OutboxPublisher first = publisherWith(firstSender, stack.outboxRepository);
        OutboxPublisher second = publisherWith(secondSender, stack.outboxRepository);

        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            pool.execute(() -> runAfter(go, first));
            pool.execute(() -> runAfter(go, second));
            go.countDown();
        }

        List<String> all = new ArrayList<>(firstSender.payloads());
        all.addAll(secondSender.payloads());

        // FOR UPDATE SKIP LOCKED: the second publisher steps over the rows the first has claimed rather
        // than blocking on them or picking them up as well.
        assertThat(all).as("every message sent exactly once between them").hasSize(40);
        assertThat(all.stream().map(OutboxPublisherIT::eventIdOf).distinct().toList()).hasSize(40);
        assertThat(stack.outboxRepository.backlog()).isZero();
    }

    @Test
    @DisplayName("an unavailable broker backs off exponentially and then gives up loudly")
    void failuresBackOffThenDie() {
        UUID orderId = place("doomed", new BigDecimal("1"), new BigDecimal("100")).id();
        stack.sender.startFailing();

        Instant start = stack.clock.instant();
        List<Duration> delays = new ArrayList<>();
        for (int attempt = 1; attempt <= 9; attempt++) {
            assertThat(stack.publisher.publishOnce()).isZero();
            OutboxRecord record = stack.outboxRepository.findByAggregate(orderId).getFirst();

            assertThat(record.attempts()).isEqualTo(attempt);
            assertThat(record.lastError()).contains("simulated broker outage");
            assertThat(record.deadAt()).isNull();
            delays.add(Duration.between(stack.clock.instant(), record.nextAttemptAt()));

            // Nothing is retried before its time: without advancing the clock the row is not even
            // claimed, which is the backoff actually taking effect rather than being merely recorded.
            assertThat(stack.publisher.publishOnce()).isZero();
            assertThat(stack.outboxRepository.findByAggregate(orderId).getFirst().attempts())
                    .isEqualTo(attempt);

            stack.clock.advance(Duration.between(start, record.nextAttemptAt()).plusSeconds(1));
        }

        assertThat(delays)
                .as("1s, 2s, 4s … capped at five minutes")
                .startsWith(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(16),
                        Duration.ofSeconds(32),
                        Duration.ofSeconds(64),
                        Duration.ofSeconds(128),
                        Duration.ofSeconds(256));

        // The tenth attempt is terminal.
        assertThat(stack.publisher.publishOnce()).isZero();
        OutboxRecord dead = stack.outboxRepository.findByAggregate(orderId).getFirst();

        assertThat(dead.attempts()).isEqualTo(10);
        assertThat(dead.deadAt()).isNotNull();
        assertThat(stack.counter("outbox_dead_total")).isEqualTo(1d);
        assertThat(stack.outboxRepository.deadCount()).isEqualTo(1);

        // Dead means it stops being retried — and stays on the table for whoever investigates.
        assertThat(stack.outboxRepository.backlog()).as("no longer counted as backlog").isZero();
        stack.clock.advance(Duration.ofHours(1));
        stack.sender.stopFailing();
        assertThat(stack.publisher.publishOnce())
                .as("a dead message is never picked up again, even once the broker returns")
                .isZero();
    }

    @Test
    @DisplayName("an out-of-order delivery is counted rather than quietly accepted")
    void outOfOrderDeliveryIsVisible() {
        Order order = place("reordered", new BigDecimal("10"), new BigDecimal("1"));
        stack.orders.submit(order.id());
        stack.publisher.publishOnce();

        List<String> payloads = stack.sender.payloads();
        assertThat(payloads).hasSize(2);

        stack.verifier.ingest(payloads.get(1));
        stack.verifier.ingest(payloads.get(0));

        assertThat(stack.counter("consumer_out_of_order_total")).isEqualTo(1d);
    }

    @Test
    @DisplayName("published rows are kept, then dropped by retention")
    void publishedRowsAreKeptThenPurged() {
        UUID orderId = place("retained", new BigDecimal("1"), new BigDecimal("100")).id();
        stack.publisher.publishOnce();

        assertThat(stack.outboxRepository.findByAggregate(orderId)).hasSize(1);

        stack.clock.advance(Duration.ofDays(8));
        assertThat(stack.publisher.purgePublished()).isEqualTo(1);
        assertThat(stack.outboxRepository.findByAggregate(orderId)).isEmpty();
    }

    private static void runAfter(CountDownLatch go, OutboxPublisher publisher) {
        try {
            go.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        publisher.publishOnce();
    }

    private OutboxPublisher publisherWith(RecordingEventSender sender, OutboxRepository repository) {
        return new OutboxPublisher(
                repository,
                sender,
                new TransactionTemplate(stack.transactionManager),
                new OutboxProperties(null, null, null, null, null, null, null, null),
                new OutboxMetrics(new SimpleMeterRegistry(), repository),
                stack.clock);
    }

    private Order place(String clientOrderId, BigDecimal quantity, BigDecimal price) {
        return stack.orders
                .place(new PlaceOrderCommand(
                        "local",
                        clientOrderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.LIMIT,
                        TimeInForce.GTC,
                        quantity,
                        price))
                .order();
    }

    private void fund(BigDecimal amount) {
        UUID external = stack.ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
        UUID available = stack.ledger.getOrCreateAccount("local", "USDT", AccountType.AVAILABLE).id();
        stack.ledger.post(
                "fund-" + UUID.randomUUID(),
                "DEPOSIT",
                null,
                List.of(PostingLine.of(external, amount.negate()), PostingLine.of(available, amount)));
    }

    private long sequenceOf(String payload) {
        try {
            return stack.json.readTree(payload).get("sequenceNo").asLong();
        } catch (Exception e) {
            throw new AssertionError("payload is not a readable envelope: " + payload, e);
        }
    }

    private static String eventIdOf(String payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(payload)
                    .get("eventId")
                    .asText();
        } catch (Exception e) {
            throw new AssertionError("payload is not a readable envelope: " + payload, e);
        }
    }

    @SuppressWarnings("unused")
    private JdbcTemplate jdbc() {
        return stack.jdbc;
    }
}
