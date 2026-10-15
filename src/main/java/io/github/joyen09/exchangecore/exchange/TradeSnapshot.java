package io.github.joyen09.exchangecore.exchange;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One fill, as the venue reported it.
 *
 * <p>{@code tradeId} is the venue's own, and is the de-duplication key together with the symbol
 * (ADR-0011). It is not optional and it is not ours to invent: a key computed from quantity, price and
 * timestamp would merge two genuinely distinct fills that happen to be identical, which is ordinary in
 * a liquid market.
 *
 * <p>The constructor refuses a negative {@code tradeId}, and that is not defensive tidiness. Binance's
 * {@code executionReport} carries {@code "t": -1} when the event is not a fill — a {@code NEW} or
 * {@code CANCELED} report ({@code docs/vendor/binance-citations.md} §4a). Building a
 * {@code TradeSnapshot} from such an event is a bug, and the only thing worse than it failing here is
 * it succeeding: {@code (symbol, -1)} would be written to {@code processed_trades} as a real key, and
 * every later non-fill event for that symbol would be silently discarded as a duplicate.
 */
public record TradeSnapshot(
        Symbol symbol,
        long tradeId,
        ClientOrderId clientOrderId,
        VenueOrderId venueOrderId,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal commission,
        String commissionAsset,
        Instant occurredAt) {

    public TradeSnapshot {
        if (symbol == null || clientOrderId == null) {
            throw new IllegalArgumentException("symbol and clientOrderId are required");
        }
        if (tradeId < 0) {
            throw new IllegalArgumentException(
                    "tradeId %d is not a trade: the venue sends -1 when the event is not a fill, and "
                                    .formatted(tradeId)
                            + "recording it would poison the (symbol, tradeId) de-duplication key");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("a fill has a positive quantity");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("a fill has a positive price");
        }
        if ((commission == null) != (commissionAsset == null)) {
            throw new IllegalArgumentException("commission and its asset are present together or not at all");
        }
    }

    /** The quote-asset value of this fill. */
    public BigDecimal notional() {
        return quantity.multiply(price);
    }
}
