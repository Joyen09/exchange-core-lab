package io.github.joyen09.exchangecore.order;

import java.util.UUID;

/** An attempted transition that the table does not allow. Always fatal to the transaction. */
public class IllegalStateTransitionException extends RuntimeException {

    private final UUID orderId;
    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalStateTransitionException(UUID orderId, OrderStatus from, OrderStatus to) {
        super("order %s cannot move from %s to %s".formatted(orderId, from, to));
        this.orderId = orderId;
        this.from = from;
        this.to = to;
    }

    public UUID orderId() {
        return orderId;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
