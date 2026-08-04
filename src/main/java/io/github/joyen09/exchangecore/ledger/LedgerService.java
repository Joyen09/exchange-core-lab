package io.github.joyen09.exchangecore.ledger;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger's write path.
 *
 * <p>Transactions are programmatic rather than {@code @Transactional}. The boundary is the thing
 * this class is about — a deferred constraint only fires at commit, and the account lock is only
 * held for the life of the transaction — so leaving that boundary implicit in a proxy would hide
 * the most important part of the design. It also lets tests drive commits and rollbacks explicitly,
 * which the tests for ADR-0004 depend on.
 */
@Service
public class LedgerService {

    private final LedgerRepository repository;
    private final TransactionTemplate transactionTemplate;

    public LedgerService(LedgerRepository repository, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    public Account getOrCreateAccount(String ownerId, String asset, AccountType type) {
        return transactionTemplate.execute(status -> repository.getOrCreateAccount(ownerId, asset, type));
    }

    /**
     * Writes one balanced entry, or returns the entry a previous call with the same key already
     * wrote.
     *
     * <p>Replay returns the original result rather than an error (SPEC §4 Phase 1), so a caller that
     * retries after a timeout cannot double-post.
     */
    public LedgerEntry post(String idempotencyKey, String kind, String refId, List<PostingLine> lines) {
        preflight(lines);
        try {
            return write(idempotencyKey, kind, refId, lines);
        } catch (TransactionSystemException commitFailure) {
            // Deferred constraints are raised by COMMIT, so they arrive as "JDBC commit failed"
            // rather than as an integrity violation. Unwrap the trigger's message; rethrow anything
            // else untouched.
            throw invariantViolation(commitFailure).orElse(commitFailure);
        }
    }

    private LedgerEntry write(String idempotencyKey, String kind, String refId, List<PostingLine> lines) {
        return transactionTemplate.execute(status -> {
            repository.assertSupportedIsolationLevel();

            Optional<UUID> entryId = repository.insertEntryIfAbsent(idempotencyKey, kind, refId);
            if (entryId.isEmpty()) {
                // The key was already used. Nothing is written; the original entry is the result.
                return repository
                        .findEntryByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotency key %s conflicted but no entry was found".formatted(idempotencyKey)));
            }

            Map<UUID, BigDecimal> movements = netByAccount(lines);

            // Locks first, every one of them, in ascending id order. Ascending order is what makes
            // two entries touching the same pair of accounts unable to deadlock. Distinct ids only:
            // an account may legitimately appear on several lines of one entry.
            Map<UUID, Account> locked = new LinkedHashMap<>();
            movements.keySet().stream().sorted().forEach(accountId -> locked.put(accountId, repository.lockAccount(accountId)));

            // ...then the balances. This order is the whole point (ADR-0005 §2): reading the balance
            // before acquiring the lock yields a value that another transaction may already have
            // invalidated, and the overdraft check would pass on it. Reversing these two loops
            // reintroduces the race silently — LedgerStatementOrderIT exists to catch exactly that.
            locked.forEach((accountId, account) -> {
                if (!account.type().requiresNonNegativeBalance()) {
                    return;
                }
                BigDecimal movement = movements.get(accountId);
                BigDecimal balance = repository.balanceUnderLock(accountId);
                if (balance.add(movement).signum() < 0) {
                    throw new InsufficientBalanceException(accountId, balance, movement);
                }
            });

            repository.insertPostings(entryId.get(), lines);

            return repository
                    .findEntryById(entryId.get())
                    .orElseThrow(() -> new IllegalStateException("entry vanished after insert"));
        });
    }

    /** The SQLSTATE every ledger trigger raises with: {@code check_violation}. */
    private static final String CHECK_VIOLATION = "23514";

    private static Optional<RuntimeException> invariantViolation(Throwable failure) {
        for (Throwable current = failure; current != null && current != current.getCause(); current = current.getCause()) {
            if (current instanceof SQLException sqlFailure && CHECK_VIOLATION.equals(sqlFailure.getSQLState())) {
                return Optional.of(new LedgerInvariantViolationException(sqlFailure.getMessage(), failure));
            }
        }
        return Optional.empty();
    }

    /**
     * Fails fast with a message naming the amounts.
     *
     * <p>Diagnostics only — the enforcement is the deferred constraint trigger, which also covers
     * writers that never run this code. See {@link UnbalancedEntryException}.
     */
    private static void preflight(List<PostingLine> lines) {
        if (lines.size() < 2) {
            throw new IllegalArgumentException(
                    "an entry needs at least two postings; got " + lines.size());
        }
        BigDecimal total = lines.stream()
                .map(PostingLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() != 0) {
            throw new UnbalancedEntryException(total);
        }
    }

    /**
     * One net movement per account. Netting before the sufficiency check is not tidiness: two
     * debits of 60 against one account each pass a per-line check against a balance of 100, and
     * must fail a netted one.
     */
    private static Map<UUID, BigDecimal> netByAccount(List<PostingLine> lines) {
        Map<UUID, BigDecimal> movements = new LinkedHashMap<>();
        for (PostingLine line : lines) {
            movements.merge(line.accountId(), line.amount(), BigDecimal::add);
        }
        return movements;
    }
}
