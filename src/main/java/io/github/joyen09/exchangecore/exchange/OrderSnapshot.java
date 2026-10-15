package io.github.joyen09.exchangecore.exchange;

import io.github.joyen09.exchangecore.order.OrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * The venue's view of one order at one moment.
 *
 * <p>Carries the venue's <em>cumulative</em> figures rather than an increment, because the whole of
 * ADR-0011's ordering argument rests on them: every report contains the full answer, so duplicates are
 * idempotent by arithmetic and out-of-order delivery stops mattering. A snapshot that reported "this
 * fill was 0.1" would put the client back in the business of adding up, which is the thing that cannot
 * survive at-least-once delivery.
 *
 * @param filledQuantity Binance's {@code z} — cumulative filled quantity
 * @param cumulativeQuote Binance's {@code Z} — cumulative quote asset transacted
 */
public record OrderSnapshot(
        Symbol symbol,
        ClientOrderId clientOrderId,
        VenueOrderId venueOrderId,
        OrderStatus status,
        BigDecimal orderQuantity,
        BigDecimal filledQuantity,
        BigDecimal cumulativeQuote,
        Instant updatedAt) {

    /** The scale of {@code NUMERIC(36,18)}, which is where this value is stored (ADR-0011). */
    public static final int PRICE_SCALE = 18;

    public OrderSnapshot {
        if (symbol == null || clientOrderId == null || status == null) {
            throw new IllegalArgumentException("symbol, clientOrderId and status are required");
        }
        if (filledQuantity == null || filledQuantity.signum() < 0) {
            throw new IllegalArgumentException("filledQuantity must be present and non-negative");
        }
        if (cumulativeQuote == null || cumulativeQuote.signum() < 0) {
            throw new IllegalArgumentException("cumulativeQuote must be present and non-negative");
        }
    }

    /**
     * The average fill price, {@code Z / z}, which is the vendor's own stated formula
     * ({@code docs/vendor/binance-citations.md} §5) rather than an inference.
     *
     * <p>The scale is mandatory, not stylistic: {@code BigDecimal.divide} without one throws
     * {@code ArithmeticException} on a non-terminating decimal, so an ordinary trade — a third of a
     * unit — would otherwise crash the event handler rather than returning a number.
     *
     * @return {@code null} when nothing has filled. Not zero: zero is a price, and the absence of a
     *     price is not a price.
     */
    public BigDecimal averageFillPrice() {
        if (filledQuantity.signum() == 0) {
            return null;
        }
        return cumulativeQuote.divide(filledQuantity, PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
