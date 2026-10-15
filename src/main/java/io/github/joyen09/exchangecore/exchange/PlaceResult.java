package io.github.joyen09.exchangecore.exchange;

/**
 * What came back from placing an order — as three different types, not one type with a field.
 *
 * <h2>Why this is a sealed hierarchy</h2>
 *
 * The caller must do <em>opposite</em> things with two of these outcomes:
 *
 * <ul>
 *   <li>{@link Accepted} — the venue took the order. Drive {@code PENDING -> SUBMITTED}.
 *   <li>{@link AlreadyExists} — this order was already placed, by an earlier delivery of the same
 *       event. Take the recovery path: the snapshot is the venue's current truth, and nothing new was
 *       created.
 *   <li>{@link Rejected} — the venue refused it. Drive {@code PENDING -> REJECTED}.
 * </ul>
 *
 * The tempting design is one {@code Rejection} record carrying a {@code reason}, with
 * {@code DUPLICATE_CLIENT_ORDER_ID} as one of its values. It is smaller and it is wrong: the duplicate
 * case is not a rejection, it is a success that happened earlier, and modelling it as a reason means
 * every caller must <em>remember</em> to test for that one value before treating the result as a
 * failure. A caller that forgets marks a live order {@code REJECTED} and releases its locked funds.
 *
 * <p>As separate types, forgetting is not expressible. A {@code switch} over a sealed interface must be
 * exhaustive, so the compiler asks the question, and adding a fourth outcome later breaks every call
 * site that has not considered it — which is the point, not a cost.
 *
 * <p>This is the README's first principle at the type level: don't remember to avoid the mistake, make
 * the mistake impossible to express. The same idea appears at startup as the endpoint allowlist with no
 * override, and in the schema as {@code CHECK (trade_id >= 0)}.
 */
public sealed interface PlaceResult {

    /** The venue accepted a new order. */
    record Accepted(VenueOrderId venueOrderId, OrderSnapshot snapshot) implements PlaceResult {
        public Accepted {
            if (venueOrderId == null || snapshot == null) {
                throw new IllegalArgumentException("an accepted placement has both an id and a snapshot");
            }
        }
    }

    /**
     * The venue already had an order under this client order id, so this delivery created nothing.
     *
     * <p>Carries the existing order's state rather than merely reporting the collision: the adapter has
     * already had to fetch it to tell this case apart from a genuine rejection (ADR-0009), so making the
     * caller ask again would be a second round trip for information already in hand.
     */
    record AlreadyExists(OrderSnapshot snapshot) implements PlaceResult {
        public AlreadyExists {
            if (snapshot == null) {
                throw new IllegalArgumentException("an existing order must come with its snapshot");
            }
        }
    }

    /**
     * The venue refused the order, deterministically. Retrying it unchanged will fail the same way.
     *
     * @param venueDetail the raw text the venue sent, for the log only. It is never a branch condition:
     *     Binance documents that codes are universal while messages may vary
     *     ({@code docs/vendor/binance-citations.md} §1).
     */
    record Rejected(RejectionReason reason, String venueDetail) implements PlaceResult {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("a rejection must say why");
            }
        }
    }
}
