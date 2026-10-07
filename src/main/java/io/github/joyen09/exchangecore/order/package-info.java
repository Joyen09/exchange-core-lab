/**
 * Order lifecycle: state machine, idempotency, order events.
 *
 * <p>Owns: the transition table, the append-only event log that is the order's source of truth, the
 * projection derived from it, and {@code client_order_id} idempotency at the domain level.
 *
 * <p>Explicitly does not: call the exchange. It records intent and emits events through the outbox;
 * the {@code exchange} module owns the wire (Phase 3).
 *
 * <p>Load-bearing decisions, documented where they are made:
 *
 * <ul>
 *   <li>{@code orders} is a projection, not the truth — see {@code docs/adr/0008-order-events-as-the-source-of-truth.md},
 *       and {@link OrderProjector} which makes that claim testable.
 *   <li>Idempotency is two layers with different jobs — see {@code docs/adr/0007-two-layer-idempotency.md}.
 *   <li>Funds are locked at creation and released at the end, never settled here; settlement is Phase 3
 *       and {@link OrderFundsLock#amountToLock} is the single place that assumption lives.
 * </ul>
 */
package io.github.joyen09.exchangecore.order;
