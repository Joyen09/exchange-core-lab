package io.github.joyen09.exchangecore.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves committed outbox rows to the broker.
 *
 * <p>Claim, send, mark — all inside one transaction. If the process dies between the send and the
 * mark, the row is still unpublished and will be sent again on restart: the delivery guarantee is
 * at-least-once, and de-duplication is the consumer's job (ADR-0006). Trying to make it
 * exactly-once here would require a transaction spanning PostgreSQL and Kafka, which is the problem
 * this pattern exists to avoid.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final EventSender sender;
    private final TransactionTemplate transactions;
    private final OutboxProperties properties;
    private final OutboxMetrics metrics;
    private final Clock clock;

    public OutboxPublisher(
            OutboxRepository repository,
            EventSender sender,
            TransactionTemplate transactions,
            OutboxProperties properties,
            OutboxMetrics metrics,
            Clock clock) {
        this.repository = repository;
        this.sender = sender;
        this.transactions = transactions;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * One polling cycle. Public so tests can drive it deterministically instead of waiting for a
     * scheduler.
     *
     * @return how many messages were acknowledged by the broker
     */
    public int publishOnce() {
        Integer published = transactions.execute(status -> {
            Instant now = clock.instant();
            List<OutboxRecord> batch = repository.claimBatch(properties.getBatchSize(), now);
            int sent = 0;
            for (OutboxRecord record : batch) {
                if (publish(record, now)) {
                    sent++;
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    private boolean publish(OutboxRecord record, Instant now) {
        try {
            sender.send(
                    properties.getTopic(),
                    // The aggregate id is the partition key: one order's events land on one partition
                    // and therefore stay in order.
                    record.aggregateId().toString(),
                    record.payload(),
                    properties.getSendTimeout());
        } catch (RuntimeException failure) {
            recordFailure(record, now, failure);
            return false;
        }

        Instant publishedAt = clock.instant();
        repository.markPublished(record.id(), publishedAt);
        metrics.published(Duration.between(record.createdAt(), publishedAt));
        return true;
    }

    private void recordFailure(OutboxRecord record, Instant now, RuntimeException failure) {
        int attempts = record.attempts() + 1;
        String reason = describe(failure);

        if (attempts >= properties.getMaxAttempts()) {
            // Terminal, loud, and kept. The row stays for diagnosis and the counter stays up until
            // someone looks at it; nothing is dropped.
            repository.markDead(record.id(), attempts, now, reason);
            metrics.died();
            log.error(
                    "outbox message {} (event {}) abandoned after {} attempts: {}",
                    record.id(),
                    record.eventId(),
                    attempts,
                    reason);
            return;
        }

        Instant nextAttemptAt = now.plus(backoffFor(attempts));
        repository.markFailed(record.id(), attempts, nextAttemptAt, reason);
        metrics.failed();
        log.warn(
                "outbox message {} failed (attempt {}/{}), retrying at {}: {}",
                record.id(),
                attempts,
                properties.getMaxAttempts(),
                nextAttemptAt,
                reason);
    }

    /** Exponential: 1s, 2s, 4s … capped, so a long broker outage does not become a busy loop. */
    Duration backoffFor(int attempts) {
        Duration backoff = properties.getInitialBackoff().multipliedBy(1L << Math.min(attempts - 1, 20));
        return backoff.compareTo(properties.getMaxBackoff()) > 0 ? properties.getMaxBackoff() : backoff;
    }

    private static String describe(RuntimeException failure) {
        Throwable cause = failure.getCause();
        return cause == null ? failure.toString() : failure + " / " + cause;
    }

    /** Retention: published rows are kept for forensics, then dropped (ADR-0006). */
    public int purgePublished() {
        Integer removed = transactions.execute(status ->
                repository.deletePublishedBefore(clock.instant().minus(properties.getRetention())));
        return removed == null ? 0 : removed;
    }
}
