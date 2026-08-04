package io.github.joyen09.exchangecore.ledger;

/**
 * Raised when the write path runs at an isolation level whose failure would be silent.
 *
 * <p>Only {@code REPEATABLE READ} qualifies (ADR-0005). Under it, the balance read taken after the
 * account row is locked still uses the transaction's original snapshot, so it does not see the
 * previous lock holder's committed postings — and nothing raises, because that holder only locked
 * the account row without modifying it, leaving no version conflict to detect.
 *
 * <p>{@code READ COMMITTED} is safe because each statement takes a fresh snapshot.
 * {@code SERIALIZABLE} is safe for a different reason: the stale read still happens, but SSI records
 * the read-write dependency and refuses the commit with SQLSTATE 40001 — which obliges the caller to
 * retry, idempotently.
 */
public class UnsupportedIsolationLevelException extends LedgerException {

    public UnsupportedIsolationLevelException(String levelName) {
        super("""
                refusing to write the ledger at isolation level %s.

                Under REPEATABLE READ the balance read taken after locking the account still uses the
                transaction's original snapshot, so it cannot see the previous holder's committed
                postings — and no error is raised, because that holder only locked the account row
                without modifying it. The overdraft check would silently pass on a stale balance.

                Use READ COMMITTED (fresh snapshot per statement) or SERIALIZABLE (stale read is
                detected at commit as a serialisation failure, which the caller must retry).
                See docs/adr/0005-balance-concurrency-control.md.
                """
                .formatted(levelName));
    }
}
