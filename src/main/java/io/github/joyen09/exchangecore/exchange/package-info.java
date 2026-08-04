/**
 * Exchange transport: Binance Spot Testnet REST and user data stream, retries, rate limiting.
 *
 * <p>Owns: HTTP and WebSocket clients, reconnect and listen-key renewal, backoff, and translation
 * between exchange payloads and internal events.
 *
 * <p>Explicitly does not: contain business rules. It has no opinion about whether an order should
 * exist, only about how to talk to the venue.
 *
 * <p>Phase 3 (SPEC §4). Empty in Phase 0 — the module boundary is declared up front (ADR-0002).
 * The endpoint allowlist that constrains this module lives in {@code guard} and is already active.
 */
package io.github.joyen09.exchangecore.exchange;
