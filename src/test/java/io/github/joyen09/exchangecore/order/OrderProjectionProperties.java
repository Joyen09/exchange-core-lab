package io.github.joyen09.exchangecore.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joyen09.exchangecore.ledger.AccountType;
import io.github.joyen09.exchangecore.ledger.PostingLine;
import io.github.joyen09.exchangecore.support.Containers;
import io.github.joyen09.exchangecore.support.OrderTestStack;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * The projection claim, as a property (SPEC §7.4).
 *
 * <p>ADR-0008 says {@code order_events} is the source of truth and {@code orders} is derived from it.
 * That is only a real claim if folding the log reproduces the row exactly, for any history the state
 * machine permits — so 500 random walks of the transition graph are generated and every one is checked
 * field for field.
 *
 * <p>The walk uses the real transition table to choose its next step, so it can only generate histories
 * the system would actually allow. A generator that produced illegal sequences would be testing the
 * error path by accident and the projection not at all.
 */
class OrderProjectionProperties {

    private static final BigDecimal QUANTITY = new BigDecimal("1000");
    private static final BigDecimal PRICE = BigDecimal.ONE;
    private static final BigDecimal FILL_STEP = BigDecimal.ONE;

    private static OrderTestStack stack;

    @BeforeContainer
    static void prepare() {
        Containers.reset();
        stack = new OrderTestStack();
        UUID external = stack.ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
        UUID available = stack.ledger.getOrCreateAccount("local", "USDT", AccountType.AVAILABLE).id();
        stack.ledger.post(
                "fund-properties",
                "DEPOSIT",
                null,
                List.of(
                        PostingLine.of(external, new BigDecimal("-100000000")),
                        PostingLine.of(available, new BigDecimal("100000000"))));
    }

    @Property(tries = 500)
    void foldingTheLogReproducesTheProjection(
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 97) Integer> choices) {

        UUID orderId = stack.orders
                .place(new PlaceOrderCommand(
                        "local",
                        "property-" + UUID.randomUUID(),
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.LIMIT,
                        TimeInForce.GTC,
                        QUANTITY,
                        PRICE))
                .order()
                .id();

        OrderStatus current = OrderStatus.PENDING;
        for (int choice : choices) {
            List<OrderStatus> targets = OrderStatusTransitions.targetsFrom(current).stream()
                    .sorted()
                    .toList();
            if (targets.isEmpty()) {
                break; // terminal: the history ends here, which is itself a case worth generating
            }
            OrderStatus next = targets.get(choice % targets.size());
            Order after = apply(orderId, current, next);
            current = after.status();
        }

        Order stored = stack.orders.require(orderId);
        Order rebuilt = stack.projector.rebuild(orderId);

        // Record equality, deliberately: every field at once, including the ones it would be easy to
        // forget — version, timestamps, and the average fill price.
        assertThat(rebuilt).isEqualTo(stored);
    }

    private Order apply(UUID orderId, OrderStatus from, OrderStatus to) {
        boolean fills = to == OrderStatus.PARTIALLY_FILLED || to == OrderStatus.FILLED;
        Fill fill = null;
        if (fills) {
            Order before = stack.orders.require(orderId);
            BigDecimal remaining = before.quantity().subtract(before.filledQuantity());
            BigDecimal quantity = to == OrderStatus.FILLED ? remaining : FILL_STEP.min(remaining);
            if (quantity.signum() > 0) {
                // A price that varies per fill, so the weighted average is actually exercised rather
                // than trivially equal to a single price.
                fill = new Fill(quantity, PRICE.add(new BigDecimal(stack.orders.require(orderId).version() % 7)));
            }
        }
        return stack.orders.transition(
                orderId,
                to,
                eventFor(to),
                fill,
                java.util.Map.of(),
                to == OrderStatus.CANCELED || to == OrderStatus.EXPIRED
                        ? OrderService.FundsEffect.RELEASE
                        : OrderService.FundsEffect.NONE);
    }

    private static OrderEventType eventFor(OrderStatus status) {
        return switch (status) {
            case PENDING -> OrderEventType.ORDER_CREATED;
            case SUBMITTED -> OrderEventType.ORDER_SUBMITTED;
            case PARTIALLY_FILLED -> OrderEventType.ORDER_PARTIALLY_FILLED;
            case CANCELING -> OrderEventType.ORDER_CANCEL_REQUESTED;
            case FILLED -> OrderEventType.ORDER_FILLED;
            case CANCELED -> OrderEventType.ORDER_CANCELED;
            case REJECTED -> OrderEventType.ORDER_REJECTED;
            case EXPIRED -> OrderEventType.ORDER_EXPIRED;
        };
    }

    @SuppressWarnings("unused")
    private static ObjectMapper unusedMapper() {
        return new ObjectMapper();
    }
}
