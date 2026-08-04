package io.github.joyen09.exchangecore.ledger;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A requested movement against one account, before it is written.
 *
 * <p>The same account may appear on more than one line of an entry — a debit and its fee, for
 * instance. The write path nets those lines together before checking sufficiency; checking each line
 * on its own would let two debits of 60 pass against a balance of 100.
 */
public record PostingLine(UUID accountId, BigDecimal amount) {

    public PostingLine {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
    }

    public static PostingLine of(UUID accountId, BigDecimal amount) {
        return new PostingLine(accountId, amount);
    }
}
