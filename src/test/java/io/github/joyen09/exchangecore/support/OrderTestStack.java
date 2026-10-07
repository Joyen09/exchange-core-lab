package io.github.joyen09.exchangecore.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joyen09.exchangecore.idempotency.CanonicalRequest;
import io.github.joyen09.exchangecore.idempotency.IdempotencyProperties;
import io.github.joyen09.exchangecore.idempotency.IdempotencyRepository;
import io.github.joyen09.exchangecore.idempotency.IdempotentRequests;
import io.github.joyen09.exchangecore.ledger.LedgerRepository;
import io.github.joyen09.exchangecore.ledger.LedgerService;
import io.github.joyen09.exchangecore.order.OrderFundsLock;
import io.github.joyen09.exchangecore.order.OrderMetrics;
import io.github.joyen09.exchangecore.order.OrderProjector;
import io.github.joyen09.exchangecore.order.OrderRepository;
import io.github.joyen09.exchangecore.order.OrderService;
import io.github.joyen09.exchangecore.outbox.OutboxMetrics;
import io.github.joyen09.exchangecore.outbox.OutboxProperties;
import io.github.joyen09.exchangecore.outbox.OutboxPublisher;
import io.github.joyen09.exchangecore.outbox.OutboxRepository;
import io.github.joyen09.exchangecore.outbox.OrderEventVerifier;
import io.github.joyen09.exchangecore.outbox.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order stack, assembled by hand against a real database.
 *
 * <p>Not a Spring context, for the same reason the ledger tests are not: these tests are about
 * transaction boundaries, commits and savepoints, so the boundaries are drawn explicitly instead of
 * being supplied by a proxy. It also keeps the Kafka listener out of the way — the publisher is driven a
 * cycle at a time rather than by a scheduler.
 *
 * <p>The transaction manager allows nested transactions, exactly as the application's does; without that
 * the funds-lock savepoint would not work and these tests would be exercising a different arrangement
 * from production.
 */
public final class OrderTestStack {

    public final JdbcTemplate jdbc;
    public final MutableClock clock;
    public final MeterRegistry meters = new SimpleMeterRegistry();
    public final ObjectMapper json = new ObjectMapper();

    public final OrderRepository orderRepository;
    public final OutboxRepository outboxRepository;
    public final ProcessedEventRepository processedEvents;
    public final IdempotencyRepository idempotencyRepository;

    public final LedgerService ledger;
    public final OrderFundsLock fundsLock;
    public final OrderService orders;
    public final OrderProjector projector;
    public final RecordingEventSender sender = new RecordingEventSender();
    public final OutboxPublisher publisher;
    public final OrderEventVerifier verifier;
    public final IdempotentRequests idempotentRequests;

    public final PlatformTransactionManager transactionManager;
    public final TransactionTemplate transactions;

    public OrderTestStack() {
        this(new OutboxProperties(null, null, null, null, null, null, null, null));
    }

    public OrderTestStack(OutboxProperties outboxProperties) {
        DataSource dataSource = Containers.dataSource();
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = MutableClock.fixedAt("2026-01-01T00:00:00Z");

        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setNestedTransactionAllowed(true);
        this.transactionManager = manager;
        this.transactions = new TransactionTemplate(manager);

        TransactionTemplate savepoints = new TransactionTemplate(manager);
        savepoints.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

        this.orderRepository = new OrderRepository(jdbc);
        this.outboxRepository = new OutboxRepository(jdbc);
        this.processedEvents = new ProcessedEventRepository(jdbc);
        this.idempotencyRepository = new IdempotencyRepository(jdbc);

        this.ledger = new LedgerService(new LedgerRepository(jdbc), transactions);
        this.fundsLock = new OrderFundsLock(ledger, savepoints);
        this.projector = new OrderProjector(orderRepository, json);
        this.orders = new OrderService(
                orderRepository, outboxRepository, fundsLock, new OrderMetrics(meters), transactions, clock, json);

        this.publisher = new OutboxPublisher(
                outboxRepository,
                sender,
                transactions,
                outboxProperties,
                new OutboxMetrics(meters, outboxRepository),
                clock);
        this.verifier = new OrderEventVerifier(processedEvents, json, clock, meters);
        this.idempotentRequests = new IdempotentRequests(
                idempotencyRepository,
                new CanonicalRequest(json),
                new IdempotencyProperties(Duration.ofHours(24), Duration.ofMinutes(5)),
                clock,
                manager);
    }

    public double counter(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0d : counter.count();
    }
}
