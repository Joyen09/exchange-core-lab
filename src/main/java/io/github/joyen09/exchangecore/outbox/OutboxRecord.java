package io.github.joyen09.exchangecore.outbox;

import java.time.Instant;
import java.util.UUID;

/** A message waiting to be published, or the forensic record of one that was. */
public record OutboxRecord(
        long id,
        String aggregateType,
        UUID aggregateId,
        UUID eventId,
        String eventType,
        String payload,
        Instant createdAt,
        Instant publishedAt,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant deadAt) {}
