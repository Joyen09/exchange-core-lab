package io.github.joyen09.exchangecore.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The current state of an order.
 *
 * <p>This is a <em>projection</em> of {@code order_events}, not the source of truth (ADR-0008).
 * {@link OrderProjector} can rebuild any instance of this from the log, and a property test asserts
 * the rebuilt value equals the stored row field for field.
 */
public record Order(
        UUID id,
        String ownerId,
        String clientOrderId,
        String symbol,
        OrderSide side,
        OrderType type,
        TimeInForce timeInForce,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal filledQuantity,
        BigDecimal avgFillPrice,
        OrderStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** Quantity still working. Zero once the order is filled or terminal. */
    public BigDecimal remainingQuantity() {
        return quantity.subtract(filledQuantity);
    }
}
