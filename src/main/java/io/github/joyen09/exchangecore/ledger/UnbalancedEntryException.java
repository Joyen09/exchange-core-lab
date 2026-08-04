package io.github.joyen09.exchangecore.ledger;

import java.math.BigDecimal;

/**
 * Raised by the pre-flight check for an entry whose postings do not sum to zero.
 *
 * <p><b>This is diagnostics, not enforcement.</b> The enforcement is the deferred constraint trigger
 * {@code postings_entry_balanced} (ADR-0004), which holds for every writer including a {@code psql}
 * session. This exception exists only so the common case fails with a message naming the caller and
 * the amounts, instead of a {@code DataIntegrityViolationException} thrown from {@code commit()}.
 *
 * <p>Deleting the trigger because this check looks equivalent would keep the friendly error and lose
 * the guarantee.
 */
public class UnbalancedEntryException extends LedgerException {

    public UnbalancedEntryException(BigDecimal total) {
        super("postings must sum to zero; they sum to " + total.toPlainString());
    }
}
