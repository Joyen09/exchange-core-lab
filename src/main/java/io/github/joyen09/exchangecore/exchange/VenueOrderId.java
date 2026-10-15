package io.github.joyen09.exchangecore.exchange;

/**
 * The id the venue assigned to an order. Opaque to us; recorded so that a human comparing our records
 * with the venue's has something to compare on.
 */
public record VenueOrderId(long value) {

    public static VenueOrderId of(long value) {
        return new VenueOrderId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
