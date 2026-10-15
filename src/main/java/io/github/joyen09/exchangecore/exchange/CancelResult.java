package io.github.joyen09.exchangecore.exchange;

/**
 * What came back from cancelling an order, split by what the caller must do next.
 *
 * <p>Same reasoning as {@link PlaceResult}: {@link AlreadyFinal} and {@link Rejected} would be one
 * record with a reason field in the smaller design, and a caller that treated "it had already filled"
 * as a failure would retry a cancel forever against an order that is never coming back.
 */
public sealed interface CancelResult {

    /** The venue accepted the cancel request. The order may still be settling. */
    record Accepted(OrderSnapshot snapshot) implements CancelResult {}

    /**
     * There was nothing to cancel: the order had already reached a terminal state. Not an error — the
     * caller asked for the order not to be live, and it is not.
     */
    record AlreadyFinal(OrderSnapshot snapshot) implements CancelResult {}

    /**
     * The venue has never heard of this client order id.
     *
     * <p>Deliberately its own case rather than a rejection reason, because it means something the
     * others do not: our record and the venue's disagree about whether an order exists at all. That is a
     * reconciliation break (Phase 4), not a cancel that failed.
     */
    record NotFound() implements CancelResult {}

    /** The venue refused the cancel for a specific, deterministic reason. */
    record Rejected(RejectionReason reason, String venueDetail) implements CancelResult {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("a rejection must say why");
            }
        }
    }
}
