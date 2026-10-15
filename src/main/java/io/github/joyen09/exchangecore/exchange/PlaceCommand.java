package io.github.joyen09.exchangecore.exchange;

import io.github.joyen09.exchangecore.order.OrderSide;
import io.github.joyen09.exchangecore.order.OrderType;
import io.github.joyen09.exchangecore.order.TimeInForce;
import java.math.BigDecimal;

/**
 * An order to place, in domain terms.
 *
 * <p>{@code clientOrderId} is the local {@code client_order_id} and is what makes a repeated
 * submission safe: the venue de-duplicates on it, and it is the only de-duplicator with the authority
 * to be right (ADR-0010). Submission is at-least-once by design, so this command will sometimes be
 * sent more than once with identical contents, and that must stay uneventful.
 */
public record PlaceCommand(
        Symbol symbol,
        ClientOrderId clientOrderId,
        OrderSide side,
        OrderType type,
        TimeInForce timeInForce,
        BigDecimal quantity,
        BigDecimal price) {

    public PlaceCommand {
        if (symbol == null || clientOrderId == null || side == null || type == null) {
            throw new IllegalArgumentException("symbol, clientOrderId, side and type are required");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (type == OrderType.LIMIT && (price == null || price.signum() <= 0)) {
            throw new IllegalArgumentException("a LIMIT order needs a positive price");
        }
    }
}
