package io.github.joyen09.exchangecore.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Raised when an account would be driven negative.
 *
 * <p>Unlike the zero-sum rule this one cannot be a database constraint: the balance is derived, so
 * there is no row to constrain (ADR-0005). What makes this application-level check sound is that it
 * runs while holding the account's row lock, against a balance read after that lock was acquired.
 */
public class InsufficientBalanceException extends LedgerException {

    private final UUID accountId;

    public InsufficientBalanceException(UUID accountId, BigDecimal balance, BigDecimal movement) {
        super("account %s has %s; requested movement of %s would leave %s"
                .formatted(
                        accountId,
                        balance.toPlainString(),
                        movement.toPlainString(),
                        balance.add(movement).toPlainString()));
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }
}
