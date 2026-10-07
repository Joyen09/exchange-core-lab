package io.github.joyen09.exchangecore.order;

import static io.github.joyen09.exchangecore.order.OrderStatus.CANCELED;
import static io.github.joyen09.exchangecore.order.OrderStatus.CANCELING;
import static io.github.joyen09.exchangecore.order.OrderStatus.EXPIRED;
import static io.github.joyen09.exchangecore.order.OrderStatus.FILLED;
import static io.github.joyen09.exchangecore.order.OrderStatus.PARTIALLY_FILLED;
import static io.github.joyen09.exchangecore.order.OrderStatus.PENDING;
import static io.github.joyen09.exchangecore.order.OrderStatus.REJECTED;
import static io.github.joyen09.exchangecore.order.OrderStatus.SUBMITTED;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The legal transitions, as data.
 *
 * <p>A table rather than a chain of conditionals, for two reasons that matter more than taste: the
 * whole matrix is readable in one screen, and a test can enumerate all 64 cells and assert each one
 * — which is how the project knows the illegal transitions are actually refused rather than merely
 * never attempted.
 */
public final class OrderStatusTransitions {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = allowed();

    private OrderStatusTransitions() {}

    private static Map<OrderStatus, Set<OrderStatus>> allowed() {
        EnumMap<OrderStatus, Set<OrderStatus>> allowed = new EnumMap<>(OrderStatus.class);

        allowed.put(PENDING, EnumSet.of(SUBMITTED, CANCELING, CANCELED, REJECTED));

        allowed.put(SUBMITTED, EnumSet.of(PARTIALLY_FILLED, CANCELING, FILLED, CANCELED, REJECTED, EXPIRED));

        // PARTIALLY_FILLED -> PARTIALLY_FILLED is allowed: each fill of a multi-fill order is its own
        // transition, accumulating quantity and average price.
        allowed.put(PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, CANCELING, FILLED, CANCELED, EXPIRED));

        // CANCELING -> PARTIALLY_FILLED / FILLED is allowed: an order can fill while its cancel
        // request is still in flight. That is what real venues do, and refusing it would make the
        // state machine reject reality rather than catch a bug. CANCELING -> CANCELING is not
        // allowed: a repeated cancel request is absorbed by the idempotent API and emits no event.
        allowed.put(CANCELING, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELED, EXPIRED));

        // Terminal states have no outgoing transitions. PENDING -> FILLED is likewise absent: an
        // order that was never sent cannot have filled, so seeing it means an upstream defect, and
        // tolerating it would hide that.
        for (OrderStatus status : OrderStatus.values()) {
            if (status.isTerminal()) {
                allowed.put(status, EnumSet.noneOf(OrderStatus.class));
            }
        }

        allowed.replaceAll((status, targets) -> Collections.unmodifiableSet(targets));
        return Collections.unmodifiableMap(allowed);
    }

    public static Set<OrderStatus> targetsFrom(OrderStatus from) {
        return ALLOWED.get(from);
    }

    public static boolean isLegal(OrderStatus from, OrderStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /**
     * @throws IllegalStateTransitionException if the transition is not in the table, which is the
     *     only way a caller is told; there is no lenient mode
     */
    public static void verify(java.util.UUID orderId, OrderStatus from, OrderStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalStateTransitionException(orderId, from, to);
        }
    }
}
