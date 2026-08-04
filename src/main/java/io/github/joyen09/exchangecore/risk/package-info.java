/**
 * Risk limits and kill switch.
 *
 * <p>Owns: per-order notional caps, daily order count caps, position caps, and a persistent kill
 * switch that survives restart and requires manual reset.
 *
 * <p>Explicitly does not: make strategy decisions. It answers "is this allowed", never "is this a
 * good idea" — this repository contains no alpha logic by design (SPEC §0.2).
 *
 * <p>Phase 4 (SPEC §4). Empty in Phase 0 — see ADR-0002.
 */
package io.github.joyen09.exchangecore.risk;
