package io.github.joyen09.exchangecore.ledger.query;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The ledger's read path.
 *
 * <p><b>This service takes no lock, so what it returns may already be out of date.</b> That is
 * deliberate: making reads lock would serialise every balance query behind every write on the
 * account, for a value that goes stale the moment it leaves the transaction anyway. A lock cannot
 * keep a number true after it has been read.
 *
 * <p>The rule that follows is the one thing about this class that can cause a real defect:
 *
 * <blockquote>
 * A balance obtained here may be <b>displayed</b>. It may never be used as an input to a write
 * decision.
 * </blockquote>
 *
 * <p>Every "is there enough?" question is answered inside the ledger write path, against a balance
 * that path computes for itself while holding the account's lock. Reading here, deciding, and then
 * writing is the overdraft race with an extra transaction boundary in the middle, where no
 * subsequent lock can rescue it.
 *
 * <p>This lives in its own package, and an ArchUnit rule forbids the ledger write package from
 * depending on it — so the misuse is not merely discouraged but unreachable from there. See
 * ADR-0005 §6.
 */
@Service
public class BalanceQueryService {

    private final JdbcTemplate jdbc;

    public BalanceQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The account's balance, for display. Possibly stale by the time the caller reads it; never an
     * input to a write decision.
     */
    public BigDecimal balanceForDisplay(UUID accountId) {
        BigDecimal balance = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE account_id = ?", BigDecimal.class, accountId);
        return balance == null ? BigDecimal.ZERO : balance;
    }
}
