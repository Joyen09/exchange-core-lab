package io.github.joyen09.exchangecore.ledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The only place ledger rows are written.
 *
 * <p>Every statement here is deliberate about ordering and locking; see {@link LedgerService} for
 * the sequence and ADR-0005 for why it is that sequence. An ArchUnit rule forbids inserting into
 * {@code postings} from anywhere else, because a second write path that forgot to lock would
 * reintroduce the overdraft race without producing an error.
 */
@Repository
public class LedgerRepository {

    /**
     * Locks the account row. The row is a mutex for a balance that is <em>not stored in it</em>;
     * nothing updates this row, which is why the intent needs stating.
     */
    static final String LOCK_ACCOUNT_SQL =
            "SELECT id, owner_id, asset, type, created_at FROM accounts WHERE id = ? FOR UPDATE";

    /** Must only ever run while the corresponding account row is locked. */
    static final String BALANCE_SQL = "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE account_id = ?";

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, row) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("owner_id"),
            rs.getString("asset"),
            AccountType.valueOf(rs.getString("type")),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Posting> POSTING_MAPPER = (rs, row) -> new Posting(
            rs.getObject("id", UUID.class),
            rs.getObject("entry_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getBigDecimal("amount"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Refuses to proceed under an isolation level whose failure would be silent (ADR-0005). Called
     * inside the transaction, before anything is read or written.
     */
    public void assertSupportedIsolationLevel() {
        Integer level = jdbc.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
        if (level != null && level == Connection.TRANSACTION_REPEATABLE_READ) {
            throw new UnsupportedIsolationLevelException("REPEATABLE READ");
        }
    }

    public Account getOrCreateAccount(String ownerId, String asset, AccountType type) {
        // ON CONFLICT DO NOTHING rather than catch-and-retry: in PostgreSQL a failed statement
        // aborts the whole transaction, so catching the duplicate key would leave nothing to
        // recover into. This blocks until a concurrent creator commits, after which the SELECT
        // below sees the winner.
        jdbc.update(
                """
                INSERT INTO accounts (id, owner_id, asset, type)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (owner_id, asset, type) DO NOTHING
                """,
                UUID.randomUUID(),
                ownerId,
                asset,
                type.name());

        return jdbc.queryForObject(
                """
                SELECT id, owner_id, asset, type, created_at
                  FROM accounts
                 WHERE owner_id = ? AND asset = ? AND type = ?
                """,
                ACCOUNT_MAPPER,
                ownerId,
                asset,
                type.name());
    }

    /**
     * Inserts the entry unless its idempotency key is already taken.
     *
     * @return the new entry id, or empty if the key already existed — in which case the caller must
     *     return the original entry rather than writing anything
     */
    public Optional<UUID> insertEntryIfAbsent(String idempotencyKey, String kind, String refId) {
        List<UUID> inserted = jdbc.query(
                """
                INSERT INTO entries (id, idempotency_key, kind, ref_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING id
                """,
                (rs, row) -> rs.getObject("id", UUID.class),
                UUID.randomUUID(),
                idempotencyKey,
                kind,
                refId);
        return inserted.stream().findFirst();
    }

    /** Acquires the account's row lock. Returns the account so its type is known under the lock. */
    public Account lockAccount(UUID accountId) {
        return jdbc.queryForObject(LOCK_ACCOUNT_SQL, ACCOUNT_MAPPER, accountId);
    }

    /**
     * The balance as seen <em>after</em> this transaction acquired the account's lock. Only valid as
     * an input to a write decision when that is true — see {@link #lockAccount(UUID)}.
     */
    public BigDecimal balanceUnderLock(UUID accountId) {
        BigDecimal balance = jdbc.queryForObject(BALANCE_SQL, BigDecimal.class, accountId);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    public void insertPostings(UUID entryId, List<PostingLine> lines) {
        jdbc.batchUpdate(
                "INSERT INTO postings (id, entry_id, account_id, amount) VALUES (?, ?, ?, ?)",
                lines.stream()
                        .map(line -> new Object[] {UUID.randomUUID(), entryId, line.accountId(), line.amount()})
                        .toList());
    }

    public Optional<LedgerEntry> findEntryById(UUID entryId) {
        return findEntry("id = ?", entryId);
    }

    public Optional<LedgerEntry> findEntryByIdempotencyKey(String idempotencyKey) {
        return findEntry("idempotency_key = ?", idempotencyKey);
    }

    private Optional<LedgerEntry> findEntry(String predicate, Object argument) {
        List<LedgerEntry> entries = jdbc.query(
                "SELECT id, idempotency_key, kind, ref_id, created_at FROM entries WHERE " + predicate,
                (rs, row) -> new LedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getString("kind"),
                        rs.getString("ref_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        List.of()),
                argument);

        return entries.stream().findFirst().map(entry -> new LedgerEntry(
                entry.id(),
                entry.idempotencyKey(),
                entry.kind(),
                entry.refId(),
                entry.createdAt(),
                postingsOf(entry.id())));
    }

    private List<Posting> postingsOf(UUID entryId) {
        return jdbc.query(
                """
                SELECT id, entry_id, account_id, amount, created_at
                  FROM postings
                 WHERE entry_id = ?
                 ORDER BY created_at, id
                """,
                POSTING_MAPPER,
                entryId);
    }
}
