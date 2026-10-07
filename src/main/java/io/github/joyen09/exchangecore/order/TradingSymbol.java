package io.github.joyen09.exchangecore.order;

/**
 * A tradable symbol and the assets it moves.
 *
 * <p>Read from the {@code symbols} table rather than derived from the symbol string: {@code ETHBTC}
 * quotes in BTC and BTC is also the base of other symbols, so suffix matching is ambiguous. An
 * unknown symbol is refused, not guessed.
 */
public record TradingSymbol(String symbol, String baseAsset, String quoteAsset) {}
