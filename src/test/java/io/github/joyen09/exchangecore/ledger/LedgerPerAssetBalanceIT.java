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
                // The whole message, not a fragment of it. The amount and the asset are the parts an
                // operator acts on, and a substring match would pass just as happily with either of
                // them wrong — which is not a hypothetical: a partial match on this project's
                // migration output let a wrong count go unnoticed, and the assertion reported green.
                .satisfies(cause -> assertThat(firstLineOf(cause))
                        .isEqualTo("ERROR: entry %s is unbalanced in BTC: postings sum to 1.000000000000000000"
                                .formatted(entryId)));

        assertThat(countPostings(entryId)).isZero();
    }

    /**
     * The count, asserted as a number rather than skimmed by a substring.
     *
     * <p>One cross-asset entry is unbalanced in <em>each</em> of its assets, so it produces two rows,
     * not one. That distinction is the substance of what the migration reports, and it is exactly the
     * thing a {@code hasMessageContaining} check reads straight past.
     *
     * <p>No trigger is tampered with to get here. The zero-sum constraint is deferred to commit, so an
     * entry is legitimately unbalanced in the middle of its own transaction — the view can be read
     * there, and the transaction then rolls back leaving nothing behind.
     */
    @Test
    @DisplayName("one cross-asset entry is reported once per asset, so the count is two")
    void theUnbalancedViewCountsCombinationsNotEntries() {
        UUID entryId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            insertEntry(entryId, "counting-" + entryId);
            insertPosting(entryId, btcAvailable, new BigDecimal("1"));
            insertPosting(entryId, usdtAvailable, new BigDecimal("-1"));

            assertThat(unbalancedCombinations(entryId))
                    .as("two assets, each unbalanced, from a single entry")
                    .isEqualTo(2);
            assertThat(unbalancedAssets(entryId)).containsExactly("BTC", "USDT");
            assertThat(unbalancedEntries(entryId)).as("but only one entry").isEqualTo(1);

            status.setRollbackOnly();
        });

        assertThat(countPostings(entryId)).as("nothing survives the rollback").isZero();
    }

    @Test
    @DisplayName("the view is empty for a healthy database")
    void theUnbalancedViewIsEmptyWhenEverythingBalances() {
        // What the migration's validation pass asserts about existing data, as a standing check rather
        // than a one-off. If this ever fails, the "all balanced" claim in V5 has stopped being true.
        transactions.executeWithoutResult(status -> {
            UUID entryId = UUID.randomUUID();
            insertEntry(entryId, "healthy-" + entryId);
            insertPosting(entryId, usdtAvailable, new BigDecimal("100"));
            insertPosting(entryId, usdtExternal, new BigDecimal("-100"));
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM unbalanced_entry_assets", Long.class))
                .isZero();
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
                .satisfies(cause -> assertThat(firstLineOf(cause))
                        .isEqualTo("ERROR: entry %s is unbalanced in BNB: postings sum to -0.000000000000000001"
                                .formatted(entryId)));

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
                .satisfies(cause -> assertThat(firstLineOf(cause))
                        .isEqualTo("ERROR: entry %s is unbalanced in USDT: postings sum to 60.000000000000000000"
                                .formatted(unbalanced)));
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

    /**
     * The message PostgreSQL raised, without the {@code Where:} frame it appends.
     *
     * <p>That frame is PL/pgSQL's stack context and carries a line number inside the trigger function,
     * so pinning it would make any edit to the function body break three tests for no benefit. The
     * first line is the message this project wrote, and it is asserted whole — the asset and the
     * amount are what an operator acts on, and a substring check would pass with either of them wrong.
     */
    private static String firstLineOf(Throwable cause) {
        return cause.getMessage().lines().findFirst().orElse("");
    }

    private long unbalancedCombinations(UUID entryId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM unbalanced_entry_assets WHERE entry_id = ?", Long.class, entryId);
    }

    private long unbalancedEntries(UUID entryId) {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT entry_id) FROM unbalanced_entry_assets WHERE entry_id = ?",
                Long.class,
                entryId);
    }

    private java.util.List<String> unbalancedAssets(UUID entryId) {
        return jdbc.queryForList(
                "SELECT asset FROM unbalanced_entry_assets WHERE entry_id = ? ORDER BY asset",
                String.class,
                entryId);
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
