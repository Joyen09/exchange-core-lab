package io.github.joyen09.exchangecore.idempotency;

import java.time.Instant;
import java.util.UUID;

/** A recorded request, either in progress ({@code httpStatus == null}) or completed. */
public record IdempotencyRecord(
        String key,
        String requestHash,
        Integer httpStatus,
        String responseBody,
        UUID resourceId,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt) {

    public boolean isComplete() {
        return httpStatus != null;
    }
}
