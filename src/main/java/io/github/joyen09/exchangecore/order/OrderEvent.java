package io.github.joyen09.exchangecore.order;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in an order's event log.
 *
 * <p>{@code sequenceNo} starts at 1 per order and is unique with {@code orderId}, which is what stops
 * two concurrent transitions from forking the stream: both compute the same next number and one of
 * them loses on the constraint.
 */
public record OrderEvent(
        long id,
        UUID orderId,
        long sequenceNo,
        OrderEventType type,
        OrderStatus fromStatus,
        OrderStatus toStatus,
        String payload,
        Instant occurredAt) {}
