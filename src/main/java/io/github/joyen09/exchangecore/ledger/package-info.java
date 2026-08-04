/**
 * Double-entry ledger: accounts, postings, entries, balance invariants.
 *
 * <p>Owns: the rule that every entry's postings sum to zero, balances derived from postings rather
 * than stored as snapshots, and idempotent writes keyed by {@code idempotency_key}.
 *
 * <p>Explicitly does not: know what an "order" is. It records value movement between accounts and
 * nothing else, which is what keeps it reusable for the optional wallet work in Phase 6.
 *
 * <p>Two decisions are load-bearing and are documented where they are made:
 *
 * <ul>
 *   <li>The zero-sum invariant is enforced by deferred constraint triggers in {@code V2__ledger.sql},
 *       not here — see {@code docs/adr/0004-zero-sum-enforcement.md}.
 *   <li>Writes serialise on the account row, locked before the balance is read — see
 *       {@code docs/adr/0005-balance-concurrency-control.md}. The read path lives in
 *       {@code ledger.query} and its results are display-only.
 * </ul>
 */
package io.github.joyen09.exchangecore.ledger;
