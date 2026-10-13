package io.github.joyen09.exchangecore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The per-asset restatement of the zero-sum invariant (ADR-0004, correction found in Phase 3).
 *
 * <p>V2 summed every posting in an entry regardless of asset, which was indistinguishable from
 * correct while every entry touched one asset. V5 groups by asset instead, and the claim made for
 * that change is that it is a <em>strengthening</em>: identical for single-asset entries, stricter
 * for cross-asset ones.
 *
 * <p>That claim is only worth making if a test can tell the two rules apart, which is the entire
 * purpose of {@link #crossAssetEntryThatSumsToZeroOverallIsStillRejected()}. Everything else here
 * exists to show the new rule did not break what the old one protected.
 *
 * <p>Like {@link LedgerZeroSumIT}, these write raw SQL rather than going through {@link LedgerService}:
 * the guarantee under test is that the <em>database</em> refuses the write, including from a writer
 * that never ran an application-level check.
 */
class LedgerPerAssetBalanceIT {

    private final JdbcTemplate jdbc = LedgerTestDatabase.jdbc();
    private final TransactionTemplate transactions = LedgerTestDatabase.transactionTemplate();
    private final LedgerService ledger = LedgerTestDatabase.ledgerService();

    private UUID usdtAvailable;
    private UUID usdtExternal;
    private UUID btcAvailable;
    private UUID btcExternal;
    private UUID bnbAvailable;
    private UUID bnbFee;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.reset();
        usdtAvailable = ledger.getOrCreateAccount("alice", "USDT", AccountType.AVAILABLE).id();
        usdtExternal = ledger.getOrCreateAccount("venue", "USDT", AccountType.EXTERNAL).id();
        btcAvailable = ledger.getOrCreateAccount("alice", "BTC", AccountType.AVAILABLE).id();
        btcExternal = ledger.getOrCreateAccount("venue", "BTC", AccountType.EXTERNAL).id();
        bnbAvailable = ledger.getOrCreateAccount("alice", "BNB", AccountType.AVAILABLE).id();
        bnbFee = ledger.getOrCreateAccount("alice", "BNB", AccountType.FEE).id();
    }

    /**
     * The test this whole revision turns on.
     *
     * <p>These two postings sum to zero overall and are nonsense: one bitcoin is not one tether. The
     * old rule accepted this entry. If this test passes against the old trigger, the restatement
     * changed nothing and the migration is ceremony.
     */
    @Test
    @DisplayName("an entry that balances overall but not per asset is rejected")
    void crossAssetEntryThatSumsToZeroOverallIsStillRejected() {
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    insertEntry(entryId, "nonsense-" + entryId);
                    insertPosting(entryId, btcAvailable, new BigDecimal("1"));
                    insertPosting(entryId, usdtAvailable, new BigDecimal("-1"));
                }))
                // Deferred to commit, so it arrives as a commit failure rather than an integrity
                // violation — the same shape ADR-0004 documents for the original trigger.
                .isInstanceOf(TransactionSystemException.class)
                .rootCause()
                .hasMessageContaining("unbalanced")
                // The message names the offending asset, because "this entry is unbalanced" is not
                // enough to act on when an entry legitimately spans three of them.
                .hasMessageContaining("BTC");

        assertThat(countPostings(entryId)).isZero();
    }

    @Test
    @DisplayName("a realistic BUY settlement, with the venue as counterparty, commits")
    void tradeSettlementCommits() {
        UUID entryId = UUID.randomUUID();

        // ADR-0004's settlement shape: 0.1 BTC bought for 6000 USDT, with a 0.0001 BTC fee.
        //   USDT: -6000 + 6000           = 0
        //   BTC:  -0.1 + 0.1 - 0.0001 + 0.0001 = 0
        transactions.executeWithoutResult(status -> {
            insertEntry(entryId, "trade:BTCUSDT:12345");
            insertPosting(entryId, usdtAvailable, new BigDecimal("-6000"));
            insertPosting(entryId, usdtExternal, new BigDecimal("6000"));
            insertPosting(entryId, btcExternal, new BigDecimal("-0.1"));
            insertPosting(entryId, btcAvailable, new BigDecimal("0.1"));
            insertPosting(entryId, btcAvailable, new BigDecimal("-0.0001"));
            insertPosting(entryId, ledger.getOrCreateAccount("alice", "BTC", AccountType.FEE)
                    .id(), new BigDecimal("0.0001"));
        });

        assertThat(countPostings(entryId)).isEqualTo(6);
    }

    @Test
    @DisplayName("one asset off by a fraction fails even though the other two balance")
    void oneAssetOutOfThreeIsEnoughToFail() {
        UUID entryId = UUID.randomUUID();

        // USDT and BTC balance; BNB is short by 1e-18, the smallest amount NUMERIC(36,18) can hold. A
        // rule that only checked the total would be nowhere near noticing this.
        //
        // The scale matters and this test got it wrong first time round: 0.0004999999999999999 is
        // nineteen decimal places, so PostgreSQL rounds it to 0.000500000000000000 on insert and the
        // entry genuinely balances. An off-by-one in the exponent produced a test that failed for a
        // reason entirely unrelated to the rule it was written for.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    insertEntry(entryId, "almost-" + entryId);
                    insertPosting(entryId, usdtAvailable, new BigDecimal("-6000"));
                    insertPosting(entryId, usdtExternal, new BigDecimal("6000"));
                    insertPosting(entryId, btcExternal, new BigDecimal("-0.1"));
                    insertPosting(entryId, btcAvailable, new BigDecimal("0.1"));
                    insertPosting(entryId, bnbAvailable, new BigDecimal("-0.0005"));
                    insertPosting(entryId, bnbFee, new BigDecimal("0.000499999999999999"));
                }))
                .isInstanceOf(TransactionSystemException.class)
                .rootCause()
                .hasMessageContaining("BNB");

        assertThat(countPostings(entryId)).isZero();
    }

    @Test
    @DisplayName("a fee in an asset the account does not hold is recorded, not refused")
    void feeInAnUnheldAssetGoesNegativeRatherThanFailing() {
        UUID entryId = UUID.randomUUID();

        // ADR-0004: non-negativity governs authorisation, not recording. The venue has already taken
        // this fee; a ledger that refuses to write it does not prevent the shortfall, it only stops
        // knowing about it. The balance goes negative and Phase 4's reconciler is what notices.
        assertThatCode(() -> transactions.executeWithoutResult(status -> {
                    insertEntry(entryId, "bnb-fee-" + entryId);
                    insertPosting(entryId, bnbAvailable, new BigDecimal("-0.0005"));
                    insertPosting(entryId, bnbFee, new BigDecimal("0.0005"));
                }))
                .doesNotThrowAnyException();

        assertThat(balanceOf(bnbAvailable)).isEqualByComparingTo("-0.0005");
    }

    @Test
    @DisplayName("single-asset entries behave exactly as they did before")
    void singleAssetEntriesAreUnaffected() {
        // The strengthening claim has two halves. The first test above shows the new rule rejects
        // something the old one accepted; this shows it accepts everything the old one did, which is
        // what makes it a strengthening rather than a different rule.
        UUID balanced = UUID.randomUUID();
        assertThatCode(() -> transactions.executeWithoutResult(status -> {
                    insertEntry(balanced, "single-ok-" + balanced);
                    insertPosting(balanced, usdtAvailable, new BigDecimal("100"));
                    insertPosting(balanced, usdtExternal, new BigDecimal("-100"));
                }))
                .doesNotThrowAnyException();

        UUID unbalanced = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    insertEntry(unbalanced, "single-bad-" + unbalanced);
                    insertPosting(unbalanced, usdtAvailable, new BigDecimal("100"));
                    insertPosting(unbalanced, usdtExternal, new BigDecimal("-40"));
                }))
                .isInstanceOf(TransactionSystemException.class)
                .rootCause()
                .hasMessageContaining("USDT");
    }

    @Test
    @DisplayName("the check is still deferred to commit, not evaluated per statement")
    @Timeout(30)
    void theInvariantIsStillDeferred() {
        UUID entryId = UUID.randomUUID();

        // An entry is built one posting at a time, so it is legitimately unbalanced in between. If
        // V5 had accidentally recreated the trigger without DEFERRABLE INITIALLY DEFERRED, this
        // would fail on the first insert — which is why the migration replaces the function body and
        // leaves the trigger alone.
        transactions.executeWithoutResult(status -> {
            insertEntry(entryId, "deferred-" + entryId);
            insertPosting(entryId, usdtAvailable, new BigDecimal("-6000"));
            assertThat(countPostings(entryId)).as("mid-entry, and nothing has complained").isEqualTo(1);
            insertPosting(entryId, usdtExternal, new BigDecimal("6000"));
        });

        assertThat(countPostings(entryId)).isEqualTo(2);
    }

    private void insertEntry(UUID entryId, String idempotencyKey) {
        jdbc.update(
                "INSERT INTO entries (id, idempotency_key, kind) VALUES (?, ?, ?)", entryId, idempotencyKey, "TEST");
    }

    private void insertPosting(UUID entryId, UUID accountId, BigDecimal amount) {
        jdbc.update(
                "INSERT INTO postings (id, entry_id, account_id, amount) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                entryId,
                accountId,
                amount);
    }

    private long countPostings(UUID entryId) {
        return jdbc.queryForObject("SELECT count(*) FROM postings WHERE entry_id = ?", Long.class, entryId);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE account_id = ?", BigDecimal.class, accountId);
    }
}
