package io.github.joyen09.exchangecore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;

/** The concurrency contract from ADR-0005, against a real PostgreSQL. */
class LedgerConcurrencyIT {

    private static final int THREADS = 20;
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("1000");
    private static final BigDecimal DEBIT = new BigDecimal("60");

    private final JdbcTemplate jdbc = LedgerTestDatabase.jdbc();
    private final LedgerService ledger = LedgerTestDatabase.ledgerService();

    private UUID available;
    private UUID external;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.reset();
        available = ledger.getOrCreateAccount("alice", "USDT", AccountType.AVAILABLE).id();
        external = ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
        fund(available, OPENING_BALANCE);
    }

    @Test
    @DisplayName("twenty concurrent debits never overdraw the account")
    void twentyConcurrentDebitsNeverOverdraw() throws Exception {
        // Evidence about outcomes, not about ordering: reversed lock/read order passes this test
        // whenever the scheduler happens to be kind. LedgerStatementOrderIT is the one that cannot.
        Concurrently.Outcome outcome = Concurrently.attempt(THREADS, () -> debit(DEBIT));

        BigDecimal expectedSpend = DEBIT.multiply(BigDecimal.valueOf(outcome.successes()));
        assertThat(balanceOf(available))
                .isEqualByComparingTo(OPENING_BALANCE.subtract(expectedSpend))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(outcome.successes()).isEqualTo(16); // floor(1000 / 60)
        assertThat(outcome.failures())
                .hasSize(THREADS - 16)
                .allMatch(InsufficientBalanceException.class::isInstance);
    }

    @Test
    @DisplayName("two debits against one account within one entry are netted before the check")
    void postingsAgainstOneAccountAreNetted() {
        UUID poor = ledger.getOrCreateAccount("bob", "USDT", AccountType.AVAILABLE).id();
        fund(poor, new BigDecimal("100"));

        // 100 >= 60 passes twice if each line is checked on its own; 100 >= 120 fails once netted.
        assertThatThrownBy(() -> ledger.post(
                        "double-debit",
                        "TRANSFER",
                        null,
                        List.of(
                                PostingLine.of(poor, new BigDecimal("-60")),
                                PostingLine.of(poor, new BigDecimal("-60")),
                                PostingLine.of(external, new BigDecimal("120")))))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(balanceOf(poor)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("transfers in opposite directions do not deadlock")
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        UUID bob = ledger.getOrCreateAccount("bob", "USDT", AccountType.AVAILABLE).id();
        fund(bob, OPENING_BALANCE);

        // Without ascending-id locking these two directions deadlock under contention; with it they
        // acquire the same locks in the same order regardless of which way value is moving.
        Concurrently.run(THREADS, () -> {
            boolean forwards = Thread.currentThread().getId() % 2 == 0;
            UUID from = forwards ? available : bob;
            UUID to = forwards ? bob : available;
            transfer(from, to, new BigDecimal("1"));
        });

        assertThat(balanceOf(available).add(balanceOf(bob)))
                .isEqualByComparingTo(OPENING_BALANCE.multiply(BigDecimal.TWO));
    }

    @Test
    @DisplayName("concurrent creation of the same account yields exactly one row")
    void concurrentAccountCreationYieldsOneRow() throws Exception {
        Set<UUID> ids = ConcurrentHashMap.newKeySet();

        Concurrently.run(THREADS, () -> ids.add(
                ledger.getOrCreateAccount("carol", "BTC", AccountType.AVAILABLE).id()));

        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM accounts WHERE owner_id = 'carol' AND asset = 'BTC'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("REPEATABLE READ is refused, because its failure would be silent")
    void repeatableReadIsRefused() {
        LedgerService atRepeatableRead = LedgerTestDatabase.ledgerService(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        assertThatThrownBy(() -> atRepeatableRead.post(
                        "repeatable-read",
                        "TRANSFER",
                        null,
                        List.of(
                                PostingLine.of(available, DEBIT.negate()),
                                PostingLine.of(external, DEBIT))))
                .isInstanceOf(UnsupportedIsolationLevelException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("SERIALIZABLE is also correct, given a retry loop — and it is not free")
    void serializableIsAlsoCorrect() throws Exception {
        // The answer to "why not just use SERIALIZABLE?", as a test rather than an opinion. It is
        // correct here for a different reason than READ COMMITTED: the stale read still happens, but
        // SSI refuses the commit. The price is that every caller owes a retry.
        LedgerService atSerializable = LedgerTestDatabase.ledgerService(TransactionDefinition.ISOLATION_SERIALIZABLE);

        Concurrently.Outcome outcome = Concurrently.attempt(THREADS, () -> withRetry(() -> atSerializable.post(
                "serializable-" + UUID.randomUUID(),
                "WITHDRAWAL",
                null,
                List.of(PostingLine.of(available, DEBIT.negate()), PostingLine.of(external, DEBIT)))));

        BigDecimal expectedSpend = DEBIT.multiply(BigDecimal.valueOf(outcome.successes()));
        assertThat(balanceOf(available))
                .isEqualByComparingTo(OPENING_BALANCE.subtract(expectedSpend))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(outcome.successes()).isEqualTo(16);
    }

    /** Retries serialisation failures. Safe only because each attempt carries its own new key. */
    private static void withRetry(Runnable action) {
        for (int attempt = 0; ; attempt++) {
            try {
                action.run();
                return;
            } catch (ConcurrencyFailureException retryable) {
                if (attempt >= 50) {
                    throw retryable;
                }
            }
        }
    }

    private void debit(BigDecimal amount) {
        ledger.post(
                "debit-" + UUID.randomUUID(),
                "WITHDRAWAL",
                null,
                List.of(PostingLine.of(available, amount.negate()), PostingLine.of(external, amount)));
    }

    private void transfer(UUID from, UUID to, BigDecimal amount) {
        ledger.post(
                "transfer-" + UUID.randomUUID(),
                "TRANSFER",
                null,
                List.of(PostingLine.of(from, amount.negate()), PostingLine.of(to, amount)));
    }

    private void fund(UUID accountId, BigDecimal amount) {
        ledger.post(
                "fund-" + UUID.randomUUID(),
                "DEPOSIT",
                null,
                List.of(PostingLine.of(external, amount.negate()), PostingLine.of(accountId, amount)));
    }

    private BigDecimal balanceOf(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE account_id = ?", BigDecimal.class, accountId);
    }
}
