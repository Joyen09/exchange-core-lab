package io.github.joyen09.exchangecore.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Publisher tuning. Defaults are the ones the acceptance tests assume. */
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    private final String topic;
    private final Duration pollInterval;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration sendTimeout;
    private final Duration retention;

    public OutboxProperties(
            String topic,
            Duration pollInterval,
            Integer batchSize,
            Integer maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            Duration sendTimeout,
            Duration retention) {
        this.topic = topic == null ? "exchange-core-lab.orders.v1" : topic;
        this.pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
        this.batchSize = batchSize == null ? 100 : batchSize;
        this.maxAttempts = maxAttempts == null ? 10 : maxAttempts;
        this.initialBackoff = initialBackoff == null ? Duration.ofSeconds(1) : initialBackoff;
        this.maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
        // SPEC §5.3: no external call without a timeout.
        this.sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
        this.retention = retention == null ? Duration.ofDays(7) : retention;
    }

    public String getTopic() {
        return topic;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public Duration getRetention() {
        return retention;
    }
}
