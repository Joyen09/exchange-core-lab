/**
 * Double-entry ledger: accounts, postings, entries, balance invariants.
 *
 * <p>Owns: the rule that every entry's postings sum to zero, balances derived from postings rather
 * than stored as snapshots, and idempotent writes keyed by {@code idempotency_key}.
 *
 * <p>Explicitly does not: know what an "order" is. It records value movement between accounts and
 * nothing else, which is what keeps it reusable for the optional wallet work in Phase 6.
 *
 * <p>Phase 1 (SPEC §4). Empty in Phase 0 — see ADR-0002.
 */
package io.github.joyen09.exchangecore.ledger;
