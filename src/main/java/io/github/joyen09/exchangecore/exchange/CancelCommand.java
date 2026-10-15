package io.github.joyen09.exchangecore.exchange;

/**
 * An order to cancel, named the way we named it.
 *
 * <p>The symbol is here because the venue requires it — cancelling by client order id alone is not a
 * request the API accepts ({@code docs/vendor/binance-citations.md}). Carrying it in the command rather
 * than looking it up inside the adapter keeps the adapter free of a database it should not have.
 */
public record CancelCommand(Symbol symbol, ClientOrderId clientOrderId) {

    public CancelCommand {
        if (symbol == null || clientOrderId == null) {
            throw new IllegalArgumentException("symbol and clientOrderId are required");
        }
    }
}
