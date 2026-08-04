/**
 * Reconciliation: three-way comparison of local order state, venue state, and ledger balances.
 *
 * <p>Owns: the periodic sweep, break classification ({@code MISSING_LOCAL}, {@code MISSING_REMOTE},
 * {@code STATE_MISMATCH}, {@code BALANCE_DRIFT}), and the break record with its snapshot.
 *
 * <p>Explicitly does not: repair anything. Breaks are alerted, never auto-corrected — automatic
 * correction in a financial system amplifies the original error.
 *
 * <p>Phase 4 (SPEC §4). Empty in Phase 0 — see ADR-0002.
 */
package io.github.joyen09.exchangecore.recon;
