package io.github.joyen09.exchangecore.exchange;

/**
 * The name <em>we</em> gave an order, which the venue echoes back and de-duplicates on.
 *
 * <p>Distinct from {@link VenueOrderId} as a type, because confusing the two is the kind of mistake
 * that produces a lookup which succeeds against the wrong order. They are both identifiers of the same
 * order and they are not interchangeable in any call.
 */
public record ClientOrderId(String value) {

    public ClientOrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("client order id must not be blank");
        }
    }

    public static ClientOrderId of(String value) {
        return new ClientOrderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
