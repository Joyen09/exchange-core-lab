package io.github.joyen09.exchangecore.order;

import io.github.joyen09.exchangecore.ledger.AccountType;
import io.github.joyen09.exchangecore.ledger.InsufficientBalanceException;
import io.github.joyen09.exchangecore.ledger.LedgerService;
import io.github.joyen09.exchangecore.ledger.PostingLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves an order's funds between {@code AVAILABLE} and {@code LOCKED}.
 *
 * <p>Phase 2 locks and releases only. Settlement — moving value to the counterparty when a fill
 * happens — is Phase 3, and until it exists the amount locked at creation is also exactly the amount
 * still locked at cancellation. {@link #amountToLock} carries that assumption in one place so Phase 3
 * has a single thing to change.
 *
 * <h2>Why the nested transaction</h2>
 *
 * Running out of funds must leave a persisted {@code REJECTED} order (SPEC §6.2), so the ledger
 * failure has to be survivable inside the enclosing transaction. A plain participating transaction
 * cannot do that: when a {@code PROPAGATION_REQUIRED} callback throws, Spring marks the whole
 * transaction rollback-only and the eventual commit fails with {@code UnexpectedRollbackException} —
 * the order would never be written. So the attempt runs at {@code PROPAGATION_NESTED}, on a savepoint,
 * and only the attempt is undone.
 *
 * <p>This is also why the ledger's non-negativity rule being an <em>application</em> check rather than
 * a database constraint matters here (ADR-0005): it throws before any failing statement, so the
 * transaction is still healthy once the savepoint is released.
 */
@Component
public class OrderFundsLock {

    private final LedgerService ledger;
    private final TransactionTemplate savepointTransactions;

    public OrderFundsLock(
            LedgerService ledger,
            @org.springframework.beans.factory.annotation.Qualifier("savepointTransactions")
                    TransactionTemplate savepointTransactions) {
        this.ledger = ledger;
        this.savepointTransactions = savepointTransactions;
    }

    /**
     * @return {@code true} when the funds are now locked, {@code false} when the owner did not have
     *     enough — in which case nothing was written to the ledger at all
     */
    public boolean tryLock(Order order, TradingSymbol symbol) {
        try {
            savepointTransactions.executeWithoutResult(status -> move(order, symbol, Direction.LOCK));
            return true;
        } catch (InsufficientBalanceException notEnough) {
            // The savepoint has already been rolled back; the enclosing transaction is unharmed and
            // the caller goes on to write ORDER_REJECTED.
            return false;
        }
    }

    /** Returns the order's locked funds. Idempotent by entry key, so a replayed release is a no-op. */
    public void release(Order order, TradingSymbol symbol) {
        move(order, symbol, Direction.RELEASE);
    }

    private void move(Order order, TradingSymbol symbol, Direction direction) {
        String asset = assetToLock(order, symbol);
        BigDecimal amount = amountToLock(order);

        UUID available = ledger.getOrCreateAccount(order.ownerId(), asset, AccountType.AVAILABLE)
                .id();
        UUID locked = ledger.getOrCreateAccount(order.ownerId(), asset, AccountType.LOCKED)
                .id();

        UUID from = direction == Direction.LOCK ? available : locked;
        UUID to = direction == Direction.LOCK ? locked : available;

        ledger.post(
                direction.entryKeyPrefix + order.id(),
                direction.entryKind,
                order.id().toString(),
                List.of(PostingLine.of(from, amount.negate()), PostingLine.of(to, amount)));
    }

    /** A buyer locks the quote asset, a seller locks the base asset. */
    public String assetToLock(Order order, TradingSymbol symbol) {
        return order.side() == OrderSide.BUY ? symbol.quoteAsset() : symbol.baseAsset();
    }

    /**
     * How much to lock, and — because Phase 2 settles nothing — also how much is still locked when the
     * order ends. <b>Phase 3 must subtract the settled quantity here</b>, or a partially filled order
     * will release more than it still holds.
     */
    public BigDecimal amountToLock(Order order) {
        return order.side() == OrderSide.BUY ? order.quantity().multiply(order.price()) : order.quantity();
    }

    private enum Direction {
        LOCK("order-lock:", "ORDER_LOCK"),
        RELEASE("order-unlock:", "ORDER_UNLOCK");

        private final String entryKeyPrefix;
        private final String entryKind;

        Direction(String entryKeyPrefix, String entryKind) {
            this.entryKeyPrefix = entryKeyPrefix;
            this.entryKind = entryKind;
        }
    }
}
