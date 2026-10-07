package io.github.joyen09.exchangecore.order;

/**
 * Order lifecycle states.
 *
 * <p>{@code CANCELING} and {@code EXPIRED} exist from the start even though Phase 2 has no venue to
 * talk to. Cancellation becomes asynchronous the moment there is one — request sent, confirmation
 * awaited — and adding the state later would mean backfilling an append-only event log rather than
 * editing an enum.
 */
public enum OrderStatus {

    /** Created locally, not yet sent anywhere. */
    PENDING(false),

    /** Accepted by the venue. */
    SUBMITTED(false),

    /** Some quantity filled, some outstanding. */
    PARTIALLY_FILLED(false),

    /** A cancel request is in flight; the outcome is not yet known. */
    CANCELING(false),

    FILLED(true),
    CANCELED(true),
    REJECTED(true),
    EXPIRED(true);

    private final boolean terminal;

    OrderStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** A terminal state has no outgoing transitions at all. */
    public boolean isTerminal() {
        return terminal;
    }
}
