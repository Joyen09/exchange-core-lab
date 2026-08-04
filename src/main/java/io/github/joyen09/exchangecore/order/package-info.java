/**
 * Order lifecycle: state machine, idempotency, order events.
 *
 * <p>Owns: {@code PENDING -> SUBMITTED -> PARTIALLY_FILLED -> FILLED} and the {@code REJECTED} /
 * {@code CANCELED} terminals, the append-only event log, and {@code client_order_id} idempotency.
 *
 * <p>Explicitly does not: call the exchange directly. It emits intent through the outbox; the
 * {@code exchange} module owns the wire.
 *
 * <p>Phase 2 (SPEC §4). Empty in Phase 0 — the package exists so the module boundary is visible
 * from the first commit rather than emerging later (see ADR-0002).
 */
package io.github.joyen09.exchangecore.order;
