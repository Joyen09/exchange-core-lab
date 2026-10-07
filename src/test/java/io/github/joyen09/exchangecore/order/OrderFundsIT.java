package io.github.joyen09.exchangecore.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joyen09.exchangecore.ledger.AccountType;
import io.github.joyen09.exchangecore.ledger.PostingLine;
import io.github.joyen09.exchangecore.ledger.query.BalanceQueryService;
import io.github.joyen09.exchangecore.support.Containers;
import io.github.joyen09.exchangecore.support.OrderTestStack;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Order and ledger, joined up (SPEC §7.5). */
class OrderFundsIT {

    private static final BigDecimal QUANTITY = new BigDecimal("0.5");
    private static final BigDecimal PRICE = new BigDecimal("60000");
    private static final BigDecimal NOTIONAL = new BigDecimal("30000");

    private OrderTestStack stack;
    private BalanceQueryService balances;
    private UUID available;
    private UUID locked;

    @BeforeEach
    void setUp() {
        Containers.reset();
        stack = new OrderTestStack();
        balances = new BalanceQueryService(stack.jdbc);
        available = stack.ledger.getOrCreateAccount("local", "USDT", AccountType.AVAILABLE).id();
        locked = stack.ledger.getOrCreateAccount("local", "USDT", AccountType.LOCKED).id();
    }

    @Test
    @DisplayName("placing a buy order moves the notional from available to locked, and balances")
    void placingLocksTheNotional() {
        fund(new BigDecimal("50000"));

        Order order = place("buy-1").order();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(balances.balanceForDisplay(available)).isEqualByComparingTo("20000");
        assertThat(balances.balanceForDisplay(locked)).isEqualByComparingTo(NOTIONAL);
        assertThat(entrySum("order-lock:" + order.id())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a sell order locks the base asset instead")
    void sellOrderLocksTheBaseAsset() {
        UUID btcAvailable = stack.ledger.getOrCreateAccount("local", "BTC", AccountType.AVAILABLE).id();
        UUID btcLocked = stack.ledger.getOrCreateAccount("local", "BTC", AccountType.LOCKED).id();
        fund("BTC", btcAvailable, new BigDecimal("2"));

        stack.orders.place(new PlaceOrderCommand(
                "local", "sell-1", "BTCUSDT", OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, QUANTITY, PRICE));

        assertThat(balances.balanceForDisplay(btcLocked)).isEqualByComparingTo(QUANTITY);
        assertThat(balances.balanceForDisplay(btcAvailable)).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("cancelling returns every locked unit")
    void cancellingReleasesTheLock() {
        fund(new BigDecimal("50000"));
        Order order = place("buy-2").order();

        stack.orders.requestCancel(order.id());

        assertThat(stack.orders.require(order.id()).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(balances.balanceForDisplay(locked)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balances.balanceForDisplay(available)).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("expiry and rejection release the lock too")
    void expiryAndRejectionRelease() {
        fund(new BigDecimal("100000"));
        UUID expiring = place("buy-expire").order().id();
        UUID rejected = place("buy-reject").order().id();
        stack.orders.submit(expiring);
        stack.orders.submit(rejected);

        stack.orders.expire(expiring);
        stack.orders.reject(rejected, "VENUE_REJECTED");

        assertThat(balances.balanceForDisplay(locked)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balances.balanceForDisplay(available)).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("too little money rejects the order and leaves the ledger untouched")
    void insufficientFundsRejectsAndWritesNoLedgerEntry() {
        fund(new BigDecimal("100"));

        Order order = place("buy-poor").order();

        // A successfully handled request with a business answer of no: the order exists, and it is
        // REJECTED rather than stuck in PENDING waiting for money that may never arrive.
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(balances.balanceForDisplay(available)).isEqualByComparingTo("100");
        assertThat(balances.balanceForDisplay(locked)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(Containers.jdbc()
                        .queryForObject(
                                "SELECT count(*) FROM entries WHERE idempotency_key LIKE 'order-%'", Long.class))
                .as("no ledger entry at all, not even a zero-value one")
                .isZero();
    }

    @Test
    @DisplayName("the rejection records why, where a later reader will find it")
    void rejectionRecordsItsReason() {
        fund(new BigDecimal("100"));

        Order order = place("buy-poor-2").order();
        List<OrderEvent> events = stack.orderRepository.eventsOf(order.id());

        assertThat(events).hasSize(2);
        assertThat(events.getLast().type()).isEqualTo(OrderEventType.ORDER_REJECTED);
        assertThat(events.getLast().payload())
                .contains("INSUFFICIENT_BALANCE")
                .contains("USDT")
                .contains("30000");
    }

    @Test
    @DisplayName("a rejected placement still emits both of its events")
    void rejectedPlacementStillPublishes() {
        fund(new BigDecimal("100"));

        Order order = place("buy-poor-3").order();

        // Both things happened — the order was created and then rejected — so both are events. The
        // savepoint only undoes the ledger attempt, not the history.
        assertThat(stack.outboxRepository.findByAggregate(order.id()))
                .extracting(record -> record.eventType())
                .containsExactly("ORDER_CREATED", "ORDER_REJECTED");
    }

    @Test
    @DisplayName("a client order id is unique per owner, not globally")
    void clientOrderIdsAreScopedToTheirOwner() {
        // The constraint is (owner_id, client_order_id). Without the owner in it, the second placement
        // here would be told it had already placed an order it has never heard of — and the caller could
        // neither predict nor see the collision, because the colliding order belongs to someone else.
        UUID otherAvailable = stack.ledger
                .getOrCreateAccount("other-owner", "USDT", AccountType.AVAILABLE)
                .id();
        fund("USDT", otherAvailable, new BigDecimal("1000000"));

        OrderService.Placement mine = place("local", "shared-name");
        OrderService.Placement theirs = place("other-owner", "shared-name");

        assertThat(mine.created()).isTrue();
        assertThat(theirs.created()).as("a different owner's identical name is not a duplicate").isTrue();
        assertThat(theirs.order().id()).isNotEqualTo(mine.order().id());

        // And within one owner it still collides, so widening the key has not weakened it.
        OrderService.Placement again = place("local", "shared-name");
        assertThat(again.created()).isFalse();
        assertThat(again.order().id()).isEqualTo(mine.order().id());
    }

    private OrderService.Placement place(String clientOrderId) {
        return place("local", clientOrderId);
    }

    private OrderService.Placement place(String ownerId, String clientOrderId) {
        return stack.orders.place(new PlaceOrderCommand(
                ownerId, clientOrderId, "BTCUSDT", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, QUANTITY, PRICE));
    }

    private void fund(BigDecimal amount) {
        fund("USDT", available, amount);
    }

    private void fund(String asset, UUID account, BigDecimal amount) {
        UUID external = stack.ledger.getOrCreateAccount("world", asset, AccountType.EXTERNAL).id();
        stack.ledger.post(
                "fund-" + UUID.randomUUID(),
                "DEPOSIT",
                null,
                List.of(PostingLine.of(external, amount.negate()), PostingLine.of(account, amount)));
    }

    private BigDecimal entrySum(String idempotencyKey) {
        return Containers.jdbc()
                .queryForObject(
                        """
                        SELECT COALESCE(SUM(p.amount), 0)
                          FROM postings p JOIN entries e ON e.id = p.entry_id
                         WHERE e.idempotency_key = ?
                        """,
                        BigDecimal.class,
                        idempotencyKey);
    }
}
