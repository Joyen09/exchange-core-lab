package io.github.joyen09.exchangecore.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joyen09.exchangecore.ledger.AccountType;
import io.github.joyen09.exchangecore.ledger.PostingLine;
import io.github.joyen09.exchangecore.support.Containers;
import io.github.joyen09.exchangecore.support.OrderTestStack;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The transition table against a real database (SPEC §7.1).
 *
 * <p>{@link OrderStatusTransitionsTest} proves the table says the right thing. This proves the write path
 * obeys it, and — the part that actually matters — that a refused transition leaves <em>nothing</em>
 * behind: no event, no outbox row, no version bump. A state machine that rejects the transition but still
 * emits the event would be worse than one with no checks at all.
 */
class OrderTransitionIT {

    private static final BigDecimal QUANTITY = new BigDecimal("0.001");
    private static final BigDecimal PRICE = new BigDecimal("100");

    private OrderTestStack stack;

    @BeforeEach
    void setUp() {
        Containers.reset();
        stack = new OrderTestStack();
        fund(new BigDecimal("1000000"));
    }

    @TestFactory
    @DisplayName("every refused transition writes nothing at all")
    Stream<DynamicTest> refusedTransitionsWriteNothing() {
        return cells(false).map(cell -> DynamicTest.dynamicTest(
                "%s -> %s is refused".formatted(cell.from(), cell.to()), () -> {
                    setUp();
                    UUID orderId = orderIn(cell.from());
                    long eventsBefore = countEvents();
                    long outboxBefore = countOutbox();
                    Order before = stack.orders.require(orderId);

                    assertThatThrownBy(() -> force(orderId, cell.to()))
                            .isInstanceOf(IllegalStateTransitionException.class);

                    assertThat(countEvents()).as("no event may be appended").isEqualTo(eventsBefore);
                    assertThat(countOutbox()).as("no outbox row may be appended").isEqualTo(outboxBefore);

                    Order after = stack.orders.require(orderId);
                    assertThat(after.status()).isEqualTo(before.status());
                    assertThat(after.version()).isEqualTo(before.version());
                }));
    }

    @TestFactory
    @DisplayName("every permitted transition is applied, logged and published")
    Stream<DynamicTest> permittedTransitionsAreApplied() {
        return cells(true).map(cell -> DynamicTest.dynamicTest(
                "%s -> %s is applied".formatted(cell.from(), cell.to()), () -> {
                    setUp();
                    UUID orderId = orderIn(cell.from());
                    long eventsBefore = countEvents();
                    long outboxBefore = countOutbox();
                    long versionBefore = stack.orders.require(orderId).version();

                    Order after = force(orderId, cell.to());

                    assertThat(after.status()).isEqualTo(cell.to());
                    assertThat(after.version()).isEqualTo(versionBefore + 1);
                    assertThat(countEvents()).isEqualTo(eventsBefore + 1);
                    assertThat(countOutbox()).isEqualTo(outboxBefore + 1);
                }));
    }

    @Test
    @DisplayName("a refused transition is counted, so a broken caller is visible")
    void refusalsAreCounted() {
        UUID orderId = orderIn(OrderStatus.PENDING);

        assertThatThrownBy(() -> force(orderId, OrderStatus.FILLED))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(stack.counter("order_transition_rejected_total", "from", "PENDING", "to", "FILLED"))
                .isEqualTo(1d);
    }

    @Test
    @DisplayName("the event log is append-only even to the owner")
    void eventLogCannotBeRewritten() {
        UUID orderId = orderIn(OrderStatus.SUBMITTED);
        List<OrderEvent> events = stack.orderRepository.eventsOf(orderId);

        assertThatThrownBy(() -> Containers.jdbc()
                        .update("UPDATE order_events SET to_status = 'FILLED' WHERE id = ?", events.getFirst().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> Containers.jdbc()
                        .update("DELETE FROM order_events WHERE id = ?", events.getFirst().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("each order's events are numbered from one, with no gaps")
    void sequenceNumbersAreDense() {
        UUID orderId = orderIn(OrderStatus.PARTIALLY_FILLED);

        assertThat(stack.orderRepository.eventsOf(orderId).stream().map(OrderEvent::sequenceNo))
                .containsExactly(1L, 2L, 3L);
    }

    private record Cell(OrderStatus from, OrderStatus to) {}

    private static Stream<Cell> cells(boolean legal) {
        return Stream.of(OrderStatus.values())
                .flatMap(from -> Stream.of(OrderStatus.values()).map(to -> new Cell(from, to)))
                .filter(cell -> OrderStatusTransitions.isLegal(cell.from(), cell.to()) == legal);
    }

    /** Forces a transition without business preconditions, so the matrix is tested and nothing else. */
    private Order force(UUID orderId, OrderStatus to) {
        return stack.orders.transition(
                orderId, to, EVENT_FOR.get(to), null, Map.of("forcedBy", "OrderTransitionIT"), OrderService.FundsEffect.NONE);
    }

    private static final Map<OrderStatus, OrderEventType> EVENT_FOR = eventTypes();

    private static Map<OrderStatus, OrderEventType> eventTypes() {
        EnumMap<OrderStatus, OrderEventType> map = new EnumMap<>(OrderStatus.class);
        map.put(OrderStatus.PENDING, OrderEventType.ORDER_CREATED);
        map.put(OrderStatus.SUBMITTED, OrderEventType.ORDER_SUBMITTED);
        map.put(OrderStatus.PARTIALLY_FILLED, OrderEventType.ORDER_PARTIALLY_FILLED);
        map.put(OrderStatus.CANCELING, OrderEventType.ORDER_CANCEL_REQUESTED);
        map.put(OrderStatus.FILLED, OrderEventType.ORDER_FILLED);
        map.put(OrderStatus.CANCELED, OrderEventType.ORDER_CANCELED);
        map.put(OrderStatus.REJECTED, OrderEventType.ORDER_REJECTED);
        map.put(OrderStatus.EXPIRED, OrderEventType.ORDER_EXPIRED);
        return map;
    }

    /** Drives a fresh order into the given state using only the real public operations. */
    private UUID orderIn(OrderStatus state) {
        UUID id = place().id();
        switch (state) {
            case PENDING -> {}
            case SUBMITTED -> stack.orders.submit(id);
            case PARTIALLY_FILLED -> {
                stack.orders.submit(id);
                stack.orders.recordFill(id, new Fill(new BigDecimal("0.0005"), PRICE));
            }
            case CANCELING -> {
                stack.orders.submit(id);
                stack.orders.requestCancel(id);
            }
            case FILLED -> {
                stack.orders.submit(id);
                stack.orders.recordFill(id, new Fill(QUANTITY, PRICE));
            }
            case CANCELED -> stack.orders.requestCancel(id);
            case REJECTED -> {
                stack.orders.submit(id);
                stack.orders.reject(id, "TEST");
            }
            case EXPIRED -> {
                stack.orders.submit(id);
                stack.orders.expire(id);
            }
        }
        assertThat(stack.orders.require(id).status()).as("precondition for this cell").isEqualTo(state);
        return id;
    }

    private Order place() {
        return stack.orders
                .place(new PlaceOrderCommand(
                        "local",
                        "order-" + UUID.randomUUID(),
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.LIMIT,
                        TimeInForce.GTC,
                        QUANTITY,
                        PRICE))
                .order();
    }

    private void fund(BigDecimal amount) {
        UUID external = stack.ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
        UUID available = stack.ledger.getOrCreateAccount("local", "USDT", AccountType.AVAILABLE).id();
        stack.ledger.post(
                "fund-" + UUID.randomUUID(),
                "DEPOSIT",
                null,
                List.of(PostingLine.of(external, amount.negate()), PostingLine.of(available, amount)));
    }

    private long countEvents() {
        return Containers.jdbc().queryForObject("SELECT count(*) FROM order_events", Long.class);
    }

    private long countOutbox() {
        return Containers.jdbc().queryForObject("SELECT count(*) FROM outbox", Long.class);
    }
}
