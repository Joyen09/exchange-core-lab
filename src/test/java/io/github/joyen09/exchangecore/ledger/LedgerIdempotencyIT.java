package io.github.joyen09.exchangecore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Replay returns the original entry rather than an error (SPEC §4 Phase 1), so a caller that retries
 * after a timeout cannot double-post.
 */
class LedgerIdempotencyIT {

    private static final int REPLAYS = 100;

    private final JdbcTemplate jdbc = LedgerTestDatabase.jdbc();
    private final LedgerService ledger = LedgerTestDatabase.ledgerService();

    private UUID available;
    private UUID external;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.reset();
        available = ledger.getOrCreateAccount("alice", "USDT", AccountType.AVAILABLE).id();
        external = ledger.getOrCreateAccount("world", "USDT", AccountType.EXTERNAL).id();
    }

    @Test
    @DisplayName("replaying the same key returns the original entry, not an error")
    void replayReturnsTheOriginalEntry() {
        LedgerEntry first = post("deposit-1");
        LedgerEntry second = post("deposit-1");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.postings()).hasSize(2);
        assertThat(entryCount()).isEqualTo(1);
        assertThat(postingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("100 sequential replays leave one entry and one movement")
    void sequentialReplaysLeaveOneEntry() {
        Set<UUID> returned = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < REPLAYS; i++) {
            returned.add(post("deposit-sequential").id());
        }

        assertThat(returned).hasSize(1);
        assertThat(entryCount()).isEqualTo(1);
        assertThat(balanceOf(available)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("100 concurrent replays leave one entry and one movement")
    void concurrentReplaysLeaveOneEntry() throws Exception {
        // The race this covers: ON CONFLICT DO NOTHING blocks until the winner commits, and only
        // then does the loser's SELECT find the entry to return. Catching a duplicate-key error
        // instead would not work — in PostgreSQL the failed statement has already aborted the
        // transaction it would need to recover in.
        Set<UUID> returned = ConcurrentHashMap.newKeySet();

        Concurrently.run(REPLAYS, () -> returned.add(post("deposit-concurrent").id()));

        assertThat(returned).as("every caller must get the same entry back").hasSize(1);
        assertThat(entryCount()).isEqualTo(1);
        assertThat(postingCount()).isEqualTo(2);
        assertThat(balanceOf(available)).isEqualByComparingTo("100");
    }

    private LedgerEntry post(String idempotencyKey) {
        return ledger.post(
                idempotencyKey,
                "DEPOSIT",
                null,
                List.of(
                        PostingLine.of(external, new BigDecimal("-100")),
                        PostingLine.of(available, new BigDecimal("100"))));
    }

    private int entryCount() {
        return jdbc.queryForObject("SELECT count(*) FROM entries", Integer.class);
    }

    private int postingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM postings", Integer.class);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE account_id = ?", BigDecimal.class, accountId);
    }
}
