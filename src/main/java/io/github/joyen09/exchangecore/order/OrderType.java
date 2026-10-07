package io.github.joyen09.exchangecore.order;

/**
 * Order type.
 *
 * <p>{@code MARKET} is modelled but refused in Phase 2: the funds to lock cannot be computed without
 * a price, and inventing one would be worse than saying no. Phase 3 revisits it with the venue's
 * order book available.
 */
public enum OrderType {
    LIMIT,
    MARKET
}
