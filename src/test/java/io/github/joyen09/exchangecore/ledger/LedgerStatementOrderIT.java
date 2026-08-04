package io.github.joyen09.exchangecore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The test that can actually catch a reversed lock/read order (ADR-0005 §2).
 *
 * <p>The twenty-thread overdraft test in {@link LedgerConcurrencyIT} is evidence about outcomes: it
 * passes against aggregate-then-lock code whenever the scheduler does not interleave badly, which is
 * most of the time. This one asserts the ordering itself, so it fails deterministically. The cost is
 * coupling to SQL text, which is why {@link #theOrderingCheckDiscriminates()} exists — an assertion
 * that never fails is worse than no assertion.
 */
class LedgerStatementOrderIT {

    private RecordingDataSource dataSource;
    private LedgerService ledger;
    private UUID available;
    private UUID external;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.reset();
        dataSource = new RecordingDataSource(LedgerTestDatabase.dataSource());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ledger = new LedgerService(
                new LedgerRepository(jdbc), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

        available = ledger.getOrCreateAccount("alice", "USDT", AccountType.AVAILABLE).id();
        external = ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
        ledger.post(
                "opening",
                "DEPOSIT",
                null,
                List.of(
                        PostingLine.of(external, new BigDecimal("-1000")),
                        PostingLine.of(available, new BigDecimal("1000"))));
        dataSource.clear();
    }

    @Test
    @DisplayName("the account lock is acquired before its balance is read")
    void lockPrecedesBalanceRead() {
        ledger.post(
                "withdrawal",
                "WITHDRAWAL",
                null,
                List.of(
                        PostingLine.of(available, new BigDecimal("-60")),
                        PostingLine.of(external, new BigDecimal("60"))));

        assertLockPrecedesBalanceRead(dataSource.statements());
    }

    @Test
    @DisplayName("the ordering check discriminates, so a passing run means something")
    void theOrderingCheckDiscriminates() {
        // Fed the reversed order, the assertion above must fail. Without this, a typo in the SQL
        // predicates would turn the real test into one that can never fail.
        List<String> reversed = List.of(
                "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE account_id = ?",
                "SELECT id, owner_id, asset, type, created_at FROM accounts WHERE id = ? FOR UPDATE");

        assertThatThrownBy(() -> assertLockPrecedesBalanceRead(reversed)).isInstanceOf(AssertionError.class);
    }

    private static void assertLockPrecedesBalanceRead(List<String> statements) {
        int lock = indexOfFirst(statements, "for update");
        int balance = indexOfFirst(statements, "sum(amount)");

        assertThat(lock).as("no FOR UPDATE was issued at all; recorded: %s", statements).isNotNegative();
        assertThat(balance).as("no balance aggregate was issued at all; recorded: %s", statements).isNotNegative();
        assertThat(lock)
                .as("the account must be locked before its balance is read; recorded: %s", statements)
                .isLessThan(balance);
    }

    private static int indexOfFirst(List<String> statements, String needle) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
