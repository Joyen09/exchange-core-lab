package io.github.joyen09.exchangecore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Property-based coverage of the invariant (SPEC §4 Phase 1: 1000 random sequences, ledger still
 * balances).
 *
 * <p>These commit for real, which is not incidental: the zero-sum rule is a deferred constraint, so
 * a property that rolled back would exercise nothing (ADR-0004).
 *
 * <p>The account pool is deliberately all {@code EXTERNAL}. The property under test is the balance
 * invariant, and external accounts carry no non-negativity rule — so a randomly generated debit
 * cannot fail for a reason that has nothing to do with what is being asserted. Overdraft behaviour
 * is covered by {@link LedgerConcurrencyIT}, where it is the subject rather than the noise.
 */
class LedgerBalanceProperties {

    private static final int ACCOUNT_POOL = 5;
    private static final int AMOUNT_SCALE = 8;

    private static JdbcTemplate jdbc;
    private static LedgerService ledger;
    private static List<UUID> accounts;

    @BeforeContainer
    static void prepareLedger() {
        jdbc = LedgerTestDatabase.jdbc();
        ledger = LedgerTestDatabase.ledgerService();
        LedgerTestDatabase.reset();

        accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNT_POOL; i++) {
            accounts.add(ledger.getOrCreateAccount("counterparty-" + i, "USDT", AccountType.EXTERNAL)
                    .id());
        }
    }

    @Property(tries = 1000)
    void everyCommittedEntryLeavesTheLedgerBalanced(@ForAll("movements") List<Movement> movements) {
        List<PostingLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Movement movement : movements) {
            BigDecimal amount = movement.amount();
            lines.add(PostingLine.of(accounts.get(movement.accountIndex()), amount));
            total = total.add(amount);
        }
        // Balance against the last account. Amounts are exact decimals, so the closing line makes
        // the entry sum to zero with no rounding: BigDecimal, never double (SPEC §5.1).
        lines.add(PostingLine.of(accounts.getLast(), total.negate()));

        LedgerEntry entry = ledger.post("property-" + UUID.randomUUID(), "RANDOM", null, lines);

        assertThat(entry.postings().stream().map(Posting::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("the entry that was just written must balance")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledgerTotal())
                .as("and so must every entry written before it")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Provide
    Arbitrary<List<Movement>> movements() {
        Arbitrary<Movement> movement = Combinators.combine(
                        Arbitraries.integers().between(0, ACCOUNT_POOL - 1),
                        Arbitraries.longs().between(-1_000_000_000L, 1_000_000_000L))
                .as((accountIndex, scaledAmount) ->
                        new Movement(accountIndex, BigDecimal.valueOf(scaledAmount, AMOUNT_SCALE)));
        return movement.list().ofMinSize(1).ofMaxSize(3);
    }

    private static BigDecimal ledgerTotal() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM postings", BigDecimal.class);
    }

    record Movement(int accountIndex, BigDecimal amount) {}
}
