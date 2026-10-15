package io.github.joyen09.exchangecore.exchange;

/**
 * A trading pair, as the venue names it.
 *
 * <p>A value type rather than a {@code String}, for one reason that is worth the file: every endpoint
 * this adapter calls requires a symbol, and several of them accept a {@code String} for other purposes
 * too. A bare {@code String} parameter is a slot that an order id, an asset or a client order id can be
 * passed into by mistake, and the compiler will agree.
 */
public record Symbol(String value) {

    public Symbol {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (!value.equals(value.toUpperCase(java.util.Locale.ROOT))) {
            // The venue is case-sensitive here and answers a generic "invalid symbol" for the lower-case
            // form, which is a confusing way to find out. Rejecting it at construction names the problem.
            throw new IllegalArgumentException("symbol must be upper case: " + value);
        }
    }

    public static Symbol of(String value) {
        return new Symbol(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
