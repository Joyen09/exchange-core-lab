package io.github.joyen09.exchangecore.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joyen09.exchangecore.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The guarantees that live in the types rather than in anyone's memory.
 *
 * <p>Nothing here talks to a venue. These are the constructors and the one piece of arithmetic, which
 * is where this phase's two documented findings are encoded — the trade id that is {@code -1} for
 * non-fill events, and the division that throws without a scale.
 */
class ExchangeTypesTest {

    @Nested
    @DisplayName("TradeSnapshot")
    class TradeSnapshots {

        @Test
        @DisplayName("refuses the venue's -1 trade id, because recording it poisons the dedupe key")
        void negativeTradeIdIsRefused() {
            // executionReport sends "t": -1 for NEW and CANCELED events. Accepting one here would put
            // (symbol, -1) into processed_trades as a real key, and the *second* non-fill event for that
            // symbol would be discarded as a duplicate — silently, and forever.
            assertThatThrownBy(() -> trade(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a trade")
                    .hasMessageContaining("de-duplication key");
        }

        @Test
        @DisplayName("accepts a real trade id, including zero")
        void realTradeIdsAreAccepted() {
            // Zero is a legitimate id and must not be swept up by a check aimed at -1. A `> 0` guard
            // would have been the easy thing to write and would reject a real fill.
            assertThatCode(() -> trade(0)).doesNotThrowAnyException();
            assertThatCode(() -> trade(12_345)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("needs the venue's order id, and tolerates the absence of ours")
        void venueOrderIdIsTheRequiredIdentifier() {
            // The REST trade list carries only orderId — no Binance trade object has a client order id
            // (docs/vendor/binance-citations.md). Requiring ours would make fetchTrades impossible to
            // implement without fabricating a value.
            assertThatCode(() -> new TradeSnapshot(
                            Symbol.of("BTCUSDT"),
                            1,
                            null,
                            VenueOrderId.of(9),
                            BigDecimal.ONE,
                            new BigDecimal("60000"),
                            null,
                            null,
                            Instant.EPOCH))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> new TradeSnapshot(
                            Symbol.of("BTCUSDT"),
                            1,
                            ClientOrderId.of("c-1"),
                            null,
                            BigDecimal.ONE,
                            new BigDecimal("60000"),
                            null,
                            null,
                            Instant.EPOCH))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("venueOrderId is the only identifier");
        }

        @Test
        @DisplayName("commission and its asset travel together or not at all")
        void commissionIsAllOrNothing() {
            assertThatThrownBy(() -> new TradeSnapshot(
                            Symbol.of("BTCUSDT"),
                            1,
                            ClientOrderId.of("c-1"),
                            VenueOrderId.of(9),
                            BigDecimal.ONE,
                            new BigDecimal("60000"),
                            new BigDecimal("0.001"),
                            null,
                            Instant.EPOCH))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("present together");
        }

        private TradeSnapshot trade(long tradeId) {
            return new TradeSnapshot(
                    Symbol.of("BTCUSDT"),
                    tradeId,
                    ClientOrderId.of("c-1"),
                    VenueOrderId.of(9),
                    BigDecimal.ONE,
                    new BigDecimal("60000"),
                    null,
                    null,
                    Instant.EPOCH);
        }
    }

    @Nested
    @DisplayName("OrderSnapshot.averageFillPrice")
    class AverageFillPrice {

        @Test
        @DisplayName("is Z / z, at the scale the column stores")
        void dividesCumulativeQuoteByCumulativeQuantity() {
            assertThat(snapshot("2", "121000").averageFillPrice()).isEqualByComparingTo("60500");
            assertThat(snapshot("2", "121000").averageFillPrice().scale()).isEqualTo(18);
        }

        @Test
        @DisplayName("does not throw on a price that does not divide evenly")
        void nonTerminatingDivisionIsRounded() {
            // BigDecimal.divide without a scale throws ArithmeticException here. A third of a unit is an
            // ordinary trade, so the unscaled form would crash the event handler in production and never
            // in a test written with round numbers.
            BigDecimal average = snapshot("3", "1").averageFillPrice();

            assertThat(average.scale()).isEqualTo(18);
            assertThat(average).isEqualByComparingTo("0.333333333333333333");
        }

        @Test
        @DisplayName("is null when nothing has filled, never zero")
        void noFillsMeansNoPrice() {
            // Zero is a price. The absence of a price is not, and writing 0 would make an unfilled order
            // indistinguishable from one that somehow filled for free.
            assertThat(snapshot("0", "0").averageFillPrice()).isNull();
        }

        private OrderSnapshot snapshot(String filled, String cumulativeQuote) {
            return new OrderSnapshot(
                    Symbol.of("BTCUSDT"),
                    ClientOrderId.of("c-1"),
                    VenueOrderId.of(9),
                    OrderStatus.PARTIALLY_FILLED,
                    new BigDecimal("10"),
                    new BigDecimal(filled),
                    new BigDecimal(cumulativeQuote),
                    Instant.EPOCH);
        }
    }

    @Nested
    @DisplayName("PlaceResult")
    class PlaceResults {

        /**
         * The reason the hierarchy is sealed: a caller cannot handle "accepted" and "rejected" and
         * quietly do nothing about a duplicate, because the compiler will not let the switch be
         * incomplete. This test would not compile if {@code AlreadyExists} were a {@code Rejected} with a
         * reason field — which is the design it exists to rule out.
         */
        @Test
        @DisplayName("forces every caller to decide what a duplicate means")
        void everyOutcomeMustBeHandled() {
            assertThat(describe(new PlaceResult.Accepted(VenueOrderId.of(1), snapshot())))
                    .isEqualTo("submitted");
            assertThat(describe(new PlaceResult.AlreadyExists(snapshot()))).isEqualTo("recover");
            assertThat(describe(new PlaceResult.Rejected(RejectionReason.INSUFFICIENT_BALANCE, "…")))
                    .isEqualTo("rejected");
        }

        private String describe(PlaceResult result) {
            // No default branch, deliberately. Adding a fourth outcome breaks this switch at compile
            // time, which is the mechanism the design is buying.
            return switch (result) {
                case PlaceResult.Accepted ignored -> "submitted";
                case PlaceResult.AlreadyExists ignored -> "recover";
                case PlaceResult.Rejected ignored -> "rejected";
            };
        }

        @Test
        @DisplayName("a duplicate is not a rejection reason")
        void duplicateIsNotARejectionReason() {
            // If someone adds DUPLICATE_CLIENT_ORDER_ID to the enum, the two outcomes become expressible
            // as one type again and the guarantee above quietly stops being a guarantee.
            assertThat(java.util.Arrays.stream(RejectionReason.values()).map(Enum::name))
                    .noneMatch(name -> name.contains("DUPLICATE"));
        }

        private OrderSnapshot snapshot() {
            return new OrderSnapshot(
                    Symbol.of("BTCUSDT"),
                    ClientOrderId.of("c-1"),
                    VenueOrderId.of(1),
                    OrderStatus.SUBMITTED,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Instant.EPOCH);
        }
    }

    @Nested
    @DisplayName("Symbol")
    class Symbols {

        @Test
        @DisplayName("refuses a lower-case symbol rather than letting the venue answer vaguely")
        void lowerCaseIsRefused() {
            assertThatThrownBy(() -> Symbol.of("btcusdt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("upper case");
        }

        @Test
        @DisplayName("refuses blank")
        void blankIsRefused() {
            assertThatThrownBy(() -> Symbol.of(" ")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
