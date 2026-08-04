package io.github.joyen09.exchangecore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The invariants from ADR-0004, tested against the real triggers.
 *
 * <p>Every test here writes through raw SQL rather than {@link LedgerService}. That is the point:
 * the guarantee being tested is that the <em>database</em> refuses these writes, including from a
 * writer that never runs the service's pre-flight check.
 */
class LedgerZeroSumIT {

    private final JdbcTemplate jdbc = LedgerTestDatabase.jdbc();
    private final TransactionTemplate transactions = LedgerTestDatabase.transactionTemplate();
    private final LedgerService ledger = LedgerTestDatabase.ledgerService();

    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.reset();
        accountA = ledger.getOrCreateAccount("alice", "USDT", AccountType.AVAILABLE).id();
        accountB = ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
    }

    @Test
    @DisplayName("an unbalanced entry is rejected at commit")
    void unbalancedEntryIsRejectedAtCommit() {
        UUID entryId = UUID.randomUUID();

        // Two postings, so the "at least two postings" trigger is satisfied and this test isolates
        // the zero-sum rule: +100 and -40 sum to 60.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    insertEntry(entryId, "unbalanced-" + entryId);
                    insertPosting(entryId, accountA, new BigDecimal("100"));
                    insertPosting(entryId, accountB, new BigDecimal("-40"));
                }))
                // A deferred constraint is raised by COMMIT, so it arrives as a commit failure
                // rather than an integrity violation — the real reason is down the cause chain.
                // LedgerService unwraps this for its callers; raw SQL writers see it as it is.
                .isInstanceOf(TransactionSystemException.class)
                .rootCause()
                .hasMessageContaining("unbalanced");

        assertThat(countEntries(entryId)).isZero();
        assertThat(countPostings(entryId)).isZero();
    }

    @Test
    @DisplayName("an unbalanced entry goes undetected when the transaction rolls back, "
            + "which is why the test above commits")
    void unbalancedEntryGoesUndetectedWhenTheTransactionRollsBack() {
        // This test asserts a limitation, not a requirement. A deferred constraint is checked at
        // COMMIT, so a rolled-back transaction never reaches it — which means a @Transactional test
        // of the invariant above would pass against a correct trigger, a broken trigger, and no
        // trigger at all. Do not "tidy" the test above to match this one.
        UUID entryId = UUID.randomUUID();

        assertThatCode(() -> transactions.executeWithoutResult(status -> {
                    insertEntry(entryId, "rolled-back-" + entryId);
                    insertPosting(entryId, accountA, new BigDecimal("100"));
                    insertPosting(entryId, accountB, new BigDecimal("-40"));
                    status.setRollbackOnly();
                }))
                .doesNotThrowAnyException();

        assertThat(countEntries(entryId)).isZero();
    }

    @Test
    @DisplayName("an entry with no postings at all is rejected at commit")
    void entryWithoutPostingsIsRejected() {
        // The zero-sum trigger fires per posting row, so this entry fires it zero times and balances
        // vacuously. Only the second trigger sees it.
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> insertEntry(entryId, "empty-" + entryId)))
                .isInstanceOf(TransactionSystemException.class)
                .rootCause()
                .hasMessageContaining("at least 2");

        assertThat(countEntries(entryId)).isZero();
    }

    @Test
    @DisplayName("a single zero posting balances but is still rejected")
    void singleZeroPostingIsRejected() {
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    insertEntry(entryId, "single-zero-" + entryId);
                    insertPosting(entryId, accountA, BigDecimal.ZERO);
                }))
                .isInstanceOf(TransactionSystemException.class)
                .rootCause()
                .hasMessageContaining("at least 2");
    }

    @Test
    @DisplayName("a balanced entry commits")
    void balancedEntryCommits() {
        UUID entryId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            insertEntry(entryId, "balanced-" + entryId);
            insertPosting(entryId, accountA, new BigDecimal("100"));
            insertPosting(entryId, accountB, new BigDecimal("-100"));
        });

        assertThat(countEntries(entryId)).isEqualTo(1);
        assertThat(countPostings(entryId)).isEqualTo(2);
    }

    @Test
    @DisplayName("postings cannot be updated")
    void postingsCannotBeUpdated() {
        UUID postingId = writeBalancedEntry();

        assertThatThrownBy(() -> jdbc.update("UPDATE postings SET amount = 1 WHERE id = ?", postingId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("postings cannot be deleted")
    void postingsCannotBeDeleted() {
        UUID postingId = writeBalancedEntry();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM postings WHERE id = ?", postingId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("UPDATE and DELETE on postings are revoked in the catalogue")
    void mutationPrivilegesAreRevoked() {
        // The second layer from ADR-0004: privileges survive ALTER TABLE ... DISABLE TRIGGER, so
        // this is not redundant with the two tests above.
        assertThat(grantedPrivileges()).contains("INSERT", "SELECT").doesNotContain("UPDATE", "DELETE");
    }

    @Test
    @DisplayName("but the revoke is inert here, because the development role is a superuser")
    void revokeIsInertForASuperuser() {
        // Worth asserting rather than assuming. A superuser bypasses ACL checks entirely, so
        // has_table_privilege still reports true even though the grant is gone from pg_class.relacl.
        // In local development and in these tests the append-only *trigger* is therefore the control
        // that actually holds; the revoke becomes operative in a deployment whose application role is
        // not a superuser. Keeping both layers is the point — neither covers the other's gap.
        assertThat(isSuperuser()).isTrue();
        assertThat(hasPrivilege("DELETE")).isTrue();
    }

    private java.util.List<String> grantedPrivileges() {
        return jdbc.queryForList(
                """
                SELECT privilege_type FROM information_schema.role_table_grants
                 WHERE table_name = 'postings' AND grantee = current_user
                """,
                String.class);
    }

    private boolean isSuperuser() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class));
    }

    private boolean hasPrivilege(String privilege) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT has_table_privilege(current_user, 'postings', ?)", Boolean.class, privilege));
    }

    private UUID writeBalancedEntry() {
        LedgerEntry entry = ledger.post(
                "entry-" + UUID.randomUUID(),
                "TEST",
                null,
                List.of(
                        PostingLine.of(accountA, new BigDecimal("100")),
                        PostingLine.of(accountB, new BigDecimal("-100"))));
        return entry.postings().getFirst().id();
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

    private int countEntries(UUID entryId) {
        return jdbc.queryForObject("SELECT count(*) FROM entries WHERE id = ?", Integer.class, entryId);
    }

    private int countPostings(UUID entryId) {
        return jdbc.queryForObject("SELECT count(*) FROM postings WHERE entry_id = ?", Integer.class, entryId);
    }
}
