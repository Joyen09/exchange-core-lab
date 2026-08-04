package io.github.joyen09.exchangecore.ledger;

/**
 * What an account is for.
 *
 * <p>The distinction that matters to the write path is {@link #requiresNonNegativeBalance()}:
 * {@link #EXTERNAL} is the ledger's window onto the world outside it, and it is <em>supposed</em> to
 * go negative — a deposit has to come from somewhere, and that somewhere is a negative posting
 * against an external account. Refusing to let it go negative would make it impossible for value to
 * enter the system at all.
 */
public enum AccountType {

    /** Spendable balance. Must never go negative. */
    AVAILABLE(true),

    /** Reserved against an open order. Must never go negative. */
    LOCKED(true),

    /** The counterparty outside this ledger. Negative by design. */
    EXTERNAL(false),

    /** Fees collected. Not constrained; a negative fee account is a reporting problem, not a loss. */
    FEE(false);

    private final boolean requiresNonNegativeBalance;

    AccountType(boolean requiresNonNegativeBalance) {
        this.requiresNonNegativeBalance = requiresNonNegativeBalance;
    }

    public boolean requiresNonNegativeBalance() {
        return requiresNonNegativeBalance;
    }
}
