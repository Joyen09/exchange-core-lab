package io.github.joyen09.exchangecore.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Idempotency record lifetime. */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    private final Duration retention;
    private final Duration abandonedAfter;

    public IdempotencyProperties(Duration retention, Duration abandonedAfter) {
        // 24 hours: long enough to cover any client retry policy worth honouring — including a human
        // retrying the next morning — and short enough that the table stays small and a replayed
        // response is never so old that returning it would be stranger than rejecting it.
        this.retention = retention == null ? Duration.ofHours(24) : retention;
        // A claim with no completion is the crash window: the claim committed, the work did not.
        // Clearing them after a few minutes keeps that from meaning 409 for the rest of the day.
        this.abandonedAfter = abandonedAfter == null ? Duration.ofMinutes(5) : abandonedAfter;
    }

    public Duration getRetention() {
        return retention;
    }

    public Duration getAbandonedAfter() {
        return abandonedAfter;
    }
}
