package io.github.joyen09.exchangecore.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joyen09.exchangecore.ledger.AccountType;
import io.github.joyen09.exchangecore.ledger.PostingLine;
import io.github.joyen09.exchangecore.order.OrderSide;
import io.github.joyen09.exchangecore.order.OrderType;
import io.github.joyen09.exchangecore.order.PlaceOrderCommand;
import io.github.joyen09.exchangecore.order.TimeInForce;
import io.github.joyen09.exchangecore.support.Containers;
import io.github.joyen09.exchangecore.support.OrderTestStack;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * One rule, written in two places, with nothing keeping them in step — until this.
 *
 * <p>"A trade id of {@code 0} is real, {@code -1} is not" is stated by {@code CHECK (trade_id >= 0)} in
 * V5 and again by {@link TradeSnapshot}'s constructor. Both are deliberate: the type stops the bad
 * value being constructed, the constraint stops it being stored by anything that bypassed the type.
 * Defence in depth is the point.
 *
 * <p>But two independent statements of one rule drift. Someone tightening the constructor to
 * {@code > 0} would reject a legitimate fill while the database kept accepting it; someone loosening
 * the constraint would open the hole the type was closing. Neither change breaks anything that
 * currently exists, which is precisely why it would go unnoticed.
 *
 * <p>So the boundary is asserted <em>once</em>, here, against both mechanisms from the same constants.
 * Change either side alone and this fails.
 *
 * <p>The rule itself comes from the venue: {@code executionReport} carries {@code "t": -1} when the
 * event is not a fill ({@code docs/vendor/binance-citations.md} §4a). Recording that as a key would let
 * the first non-fill event claim {@code (symbol, -1)}, and every later one would be discarded as a
 * duplicate.
 */
class TradeIdBoundaryIT {

    /** The smallest trade id the venue can legitimately issue. Zero is a real id, not a sentinel. */
    private static final long LOWEST_REAL_TRADE_ID = 0L;

    /** What the venue sends when the event is not a fill at all. */
    private static final long NOT_A_TRADE = -1L;

    private OrderTestStack stack;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        Containers.reset();
        stack = new OrderTestStack();
        UUID external = stack.ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
        UUID available = stack.ledger.getOrCreateAccount("local", "USDT", AccountType.AVAILABLE).id();
        stack.ledger.post(
                "fund-" + UUID.randomUUID(),
                "DEPOSIT",
                null,
                List.of(PostingLine.of(external, new BigDecimal("-1000000")), PostingLine.of(available, new BigDecimal("1000000"))));
        orderId = stack.orders
                .place(new PlaceOrderCommand(
                        "local",
                        "boundary-" + UUID.randomUUID(),
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.LIMIT,
                        TimeInForce.GTC,
                        new BigDecimal("1"),
                        new BigDecimal("100")))
                .order()
                .id();
    }

    @Test
    @DisplayName("zero is accepted by both the type and the schema")
    void theLowestRealTradeIdIsAcceptedEverywhere() {
        assertThatCode(() -> snapshotWith(LOWEST_REAL_TRADE_ID))
                .as("the type must not reject a real fill")
                .doesNotThrowAnyException();

        assertThatCode(() -> insertProcessedTrade(LOWEST_REAL_TRADE_ID))
                .as("and neither must the schema")
                .doesNotThrowAnyException();

        assertThat(storedTradeIds()).containsExactly(LOWEST_REAL_TRADE_ID);
    }

    @Test
    @DisplayName("minus one is refused by both the type and the schema")
    void theNonTradeSentinelIsRefusedEverywhere() {
        assertThatThrownBy(() -> snapshotWith(NOT_A_TRADE))
                .as("the type refuses to build it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("de-duplication key");

        assertThatThrownBy(() -> insertProcessedTrade(NOT_A_TRADE))
                .as("and the schema refuses to store it, for anything that got past the type")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_processed_trades_id");

        assertThat(storedTradeIds()).as("nothing was written").isEmpty();
    }

    @Test
    @DisplayName("the two mechanisms agree on where the boundary is")
    void bothMechanismsDrawTheLineInTheSamePlace() {
        // The assertion that actually prevents drift: rather than two tests that happen to use the same
        // numbers, this walks the values around the boundary and requires the type and the schema to
        // give the same verdict for each. A change to one side alone makes a row disagree.
        for (long tradeId : new long[] {-2L, NOT_A_TRADE, LOWEST_REAL_TRADE_ID, 1L}) {
            boolean typeAccepts = accepts(() -> snapshotWith(tradeId));
            boolean schemaAccepts = accepts(() -> insertProcessedTrade(tradeId));

            assertThat(typeAccepts)
                    .as("trade id %d: TradeSnapshot and CHECK (trade_id >= 0) must agree", tradeId)
                    .isEqualTo(schemaAccepts);
            assertThat(typeAccepts)
                    .as("trade id %d: expected acceptance exactly when the id is a real one", tradeId)
                    .isEqualTo(tradeId >= LOWEST_REAL_TRADE_ID);
        }
    }

    private static boolean accepts(Runnable attempt) {
        try {
            attempt.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TradeSnapshot snapshotWith(long tradeId) {
        return new TradeSnapshot(
                Symbol.of("BTCUSDT"),
                tradeId,
                ClientOrderId.of("c-1"),
                VenueOrderId.of(1),
                BigDecimal.ONE,
                new BigDecimal("100"),
                null,
                null,
                Instant.EPOCH);
    }

    private void insertProcessedTrade(long tradeId) {
        stack.jdbc.update(
                """
                INSERT INTO processed_trades
                    (symbol, trade_id, order_id, quantity, price, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                "BTCUSDT",
                tradeId,
                orderId,
                BigDecimal.ONE,
                new BigDecimal("100"),
                java.sql.Timestamp.from(Instant.EPOCH));
    }

    private List<Long> storedTradeIds() {
        return stack.jdbc.queryForList("SELECT trade_id FROM processed_trades ORDER BY trade_id", Long.class);
    }
}
