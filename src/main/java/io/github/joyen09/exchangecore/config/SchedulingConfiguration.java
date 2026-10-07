package io.github.joyen09.exchangecore.config;

import io.github.joyen09.exchangecore.idempotency.IdempotentRequests;
import io.github.joyen09.exchangecore.outbox.OutboxProperties;
import io.github.joyen09.exchangecore.outbox.OutboxPublisher;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Registers the background work programmatically rather than with {@code @Scheduled}.
 *
 * <p>The poll interval is a {@link Duration} property, and {@code @Scheduled}'s string attributes parse
 * only plain milliseconds or ISO-8601 — so {@code outbox.poll-interval: 200ms}, which is how every other
 * duration in this application is written, would fail at startup. Registering here keeps the
 * configuration consistent and the failure mode impossible.
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfiguration.class);

    private final OutboxPublisher publisher;
    private final IdempotentRequests idempotentRequests;
    private final OutboxProperties outboxProperties;

    public SchedulingConfiguration(
            OutboxPublisher publisher, IdempotentRequests idempotentRequests, OutboxProperties outboxProperties) {
        this.publisher = publisher;
        this.idempotentRequests = idempotentRequests;
        this.outboxProperties = outboxProperties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::publishOutbox, outboxProperties.getPollInterval());
        registrar.addFixedDelayTask(this::purge, Duration.ofMinutes(10));
    }

    private void publishOutbox() {
        try {
            publisher.publishOnce();
        } catch (RuntimeException e) {
            // A scheduled task that throws is silently cancelled by some executors; the publisher must
            // keep polling after a bad cycle, so the failure is logged and the loop continues.
            log.error("outbox publishing cycle failed; will retry on the next tick", e);
        }
    }

    private void purge() {
        try {
            int outbox = publisher.purgePublished();
            int keys = idempotentRequests.purge();
            if (outbox > 0 || keys > 0) {
                log.info("retention sweep removed {} published outbox rows and {} idempotency keys", outbox, keys);
            }
        } catch (RuntimeException e) {
            log.error("retention sweep failed; will retry on the next tick", e);
        }
    }
}
