package io.github.joyen09.exchangecore.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Outbox instrumentation (SPEC §5.4). Phase 2 only requires these to be scrapable; the dashboards are
 * Phase 5.
 *
 * <p>{@code outbox_dead_total} is the one that matters operationally: a dead message is a side effect
 * the rest of the system believes happened and the broker never heard about. It must be impossible for
 * that to be silent.
 */
@Component
public class OutboxMetrics {

    private final Timer publishLatency;
    private final Counter dead;
    private final Counter publishFailures;

    public OutboxMetrics(MeterRegistry registry, OutboxRepository repository) {
        this.publishLatency = Timer.builder("outbox_publish_latency_seconds")
                .description("From the business transaction committing to the broker acknowledging")
                .register(registry);
        this.dead = Counter.builder("outbox_dead_total")
                .description("Messages abandoned after exhausting retries — each one needs a human")
                .register(registry);
        this.publishFailures = Counter.builder("outbox_publish_failures_total")
                .description("Send attempts that failed and will be retried")
                .register(registry);

        registry.gauge("outbox_backlog", repository, OutboxRepository::backlog);
    }

    public void published(Duration latency) {
        publishLatency.record(latency);
    }

    public void failed() {
        publishFailures.increment();
    }

    public void died() {
        dead.increment();
    }
}
