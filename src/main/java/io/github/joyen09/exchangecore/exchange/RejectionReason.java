package io.github.joyen09.exchangecore.exchange;

/**
 * Why the venue refused an order, in terms the rest of the system can act on.
 *
 * <p>This enum is the translation boundary. Binance's numeric codes are overloaded — {@code -2010}
 * covers both "duplicate client order id" and "insufficient balance", distinguished only by a message
 * string the vendor reserves the right to change (see {@code docs/vendor/binance-citations.md} §1 and
 * §2) — so the adapter resolves them before they get this far, and nothing outside the adapter ever
 * sees a number.
 *
 * <p>{@code DUPLICATE_CLIENT_ORDER_ID} is deliberately <b>not</b> a member. A duplicate is not a
 * rejection: the order exists and the caller should look at it. That outcome is
 * {@link PlaceResult.AlreadyExists}, a different type with a different payload, so no caller can reach
 * the duplicate case by forgetting to check a reason.
 */
public enum RejectionReason {

    /** The account cannot cover the order. The local funds lock and the venue disagreed. */
    INSUFFICIENT_BALANCE,

    /** Quantity, price or notional outside the symbol's filters — lot size, tick size, min notional. */
    FILTER_VIOLATION,

    /** The venue does not trade this symbol, or it is halted. */
    SYMBOL_NOT_TRADING,

    /** The order type or time-in-force is not enabled for this symbol. */
    ORDER_TYPE_NOT_SUPPORTED,

    /** The request was refused for a reason that is specific, deterministic, and not one of the above. */
    REFUSED_BY_VENUE;
}
