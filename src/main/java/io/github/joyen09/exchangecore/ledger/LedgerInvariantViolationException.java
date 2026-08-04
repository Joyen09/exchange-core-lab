package io.github.joyen09.exchangecore.ledger;

/**
 * A database-enforced ledger invariant refused the write at commit.
 *
 * <p>This exists because of how deferred constraints actually surface. A statement-time violation is
 * translated by Spring into a {@code DataIntegrityViolationException}; a <em>deferred</em> one is
 * raised by {@code COMMIT}, which the transaction manager reports as a
 * {@code TransactionSystemException} — "JDBC commit failed" — a class that is not even a
 * {@code DataAccessException}, with the real reason buried two levels down the cause chain.
 *
 * <p>Callers should not have to know that. The write path unwraps it and rethrows this instead,
 * carrying the trigger's own message. The unwrapping is a convenience over the enforcement, never a
 * substitute for it.
 */
public class LedgerInvariantViolationException extends LedgerException {

    public LedgerInvariantViolationException(String databaseMessage, Throwable cause) {
        super("a ledger invariant refused this write at commit: " + databaseMessage);
        initCause(cause);
    }
}
