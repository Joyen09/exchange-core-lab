package io.github.joyen09.exchangecore.ledger;

/** Base type for ledger rule violations that the caller can act on. */
public abstract class LedgerException extends RuntimeException {

    protected LedgerException(String message) {
        super(message);
    }
}
