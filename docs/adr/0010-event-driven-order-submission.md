# ADR-0010 — Orders reach the venue through an event, not through the request

- **Status:** Accepted
- **Date:** 2026-10-13
- **Phase:** 3

## Context

Phase 2 ends with an order persisted as `PENDING`, its funds locked, its `ORDER_CREATED` event in the
log and a row in the outbox. Phase 3 has to get it to Binance. The question is who makes that call, and
when.

The constraint that is already written down: SPEC §Modules says the `order` package does not call the
exchange — it emits intent through the outbox, and the `exchange` module owns the wire. That rules out
the order service dialling out directly. It does not settle whether the *HTTP request thread* waits for
the venue.

## Options considered

### A. Submit synchronously inside the request

The controller places the order, then calls the exchange before responding.

**For:** and this is a genuinely strong case, not a straw man. The client learns the outcome in one
round trip: a venue rejection — bad symbol, lot size, insufficient balance at the venue — comes back as
part of the response it was already waiting for, instead of having to be discovered later by polling.
There is one code path, one transaction boundary to reason about, and no second process that can fall
behind or stop. Most order APIs behave this way and clients expect it.

**Against:** it binds the client's latency to Binance's, including Binance's bad minutes, and it makes
the exchange an availability dependency of accepting an order. Worse is what it does to failure: when
the venue call times out, the request thread holds an order that may or may not exist at the venue, and
it must decide something *now* — while the one action that would resolve the ambiguity, querying the
venue, is exactly what just timed out. Any answer it gives is a guess, and a guess about whether an
order exists is a guess about money.

### B. A scheduled poller over `PENDING` orders

A job scans for `PENDING` orders and submits them.

**For:** no broker involvement, and recovery is inherent — anything left `PENDING` is retried by
construction, including orders stranded by a crash.

**Against:** it reintroduces the polling latency the outbox already pays for, now twice in series.
It also needs its own claim mechanism to stop two instances submitting the same order, which is a
second copy of the machinery the outbox already has (`FOR UPDATE SKIP LOCKED`). Reasonable, and
redundant with what exists.

### C. A submitter consuming the outbox stream — chosen

The outbox publisher puts `ORDER_CREATED` on the topic; a `submitter` consumes it and calls
`ExchangeClient.place()`, then drives `PENDING → SUBMITTED` or `PENDING → REJECTED` through the Phase 2
state machine.

**For:** the request returns as soon as the order is durably recorded, so accepting an order does not
depend on the venue being up. It reuses the delivery machinery that already exists and is already
tested rather than building a second one. And the ordering guarantee the system needs is the one the
outbox already provides: an order cannot be submitted before it is persisted, because the event does
not exist until the transaction commits.

**Against:** the client no longer learns the venue's verdict in the response, and must poll
`GET /api/v1/orders/{id}` or consume the event stream. Delivery is at-least-once, so the same order may
be submitted more than once. There is now a consumer that can fall behind or stop, and an order can sit
in `PENDING` with nobody noticing unless something is watching.

## Decision

Option C.

### The response code changes from 201 to 202 — and not everywhere

`POST /api/v1/orders` returns **202 Accepted** for an order that is `PENDING`: the request has been
accepted and the work continues after the response.

It keeps **201 Created** for an order that reached a terminal state *within the request* — today that
means an order rejected for insufficient local funds. 202 promises that processing continues; for an
order that will never move again, that promise is false. The status code should not describe a future
that is not coming.

This supersedes part of ADR-0007, which argued for 201 on every placement. That argument was correct
under Phase 2's design, where every accepted order was complete when the response left. It stops being
correct the moment a second actor finishes the work. ADR-0007 carries a correction section saying so.

The rule underneath is unchanged and is now worth more, not less:

> **The status code describes what happened to the request. The order's state is in the body.**

Because the code is now 201 *or* 202 depending on the outcome, the README and the API documentation say
plainly: **clients branch on `status` in the body, not on the status code.** The code is a hint about
the request; `status` is the contract.

### Duplicate submission is designed for, not guarded against

Kafka delivery is at-least-once, so the submitter will sometimes place the same order twice. Three
mechanisms make that uneventful, and they are already in place or are the minimum addition:

1. **Every `place()` carries the same `newClientOrderId`** — the local `client_order_id`. The venue
   itself is the deduplicator, which is the only deduplicator with the authority to be right.
2. **A duplicate is recognised and resolved by asking, not by parsing.** Binance answers `-2010`, a code
   it also uses for insufficient balance, so the adapter issues
   `GET /api/v3/order?origClientOrderId=…`: if the order exists this was a duplicate and its state is
   fetched; if not, it was a real rejection. The reasoning is in ADR-0009.
3. **The state machine refuses the second transition.** A redelivered event that reaches a
   `SUBMITTED` order attempts `SUBMITTED → SUBMITTED`, which is not in the table; the submitter treats
   the refusal as ordinary duplicate consumption, counts it, and acknowledges.

**This path is the whole of acceptance criterion 4.** "Retry on 5xx without creating a duplicate order"
is not a special case bolted on for the test — it is the same mechanism that handles every redelivery,
exercised with a scripted failure.

### A submission that keeps failing leaves the order `PENDING`

When `place()` fails in a way that may be retryable, the submitter does not acknowledge, and Kafka
redelivers with backoff. After a threshold of consecutive failures it increments `submit_stuck_total`
and alerts.

**It must never transition the order to `REJECTED` on its own.** `REJECTED` is a claim that the venue
refused the order. After a timeout we do not know whether the venue refused it, accepted it, or never
saw it — and writing `REJECTED` would release the locked funds for an order that may be live at the
exchange. The honest state for "we do not know" is the state that says nothing happened yet, plus a
metric that says a human should look. Phase 4's reconciler resolves it by asking the venue, which is the
only thing that can.

## What we gave up

- **The client's one-round-trip answer.** A caller that wants to know whether Binance accepted the order
  must poll `GET /api/v1/orders/{id}` or consume the stream. For an interactive user this is a real
  downgrade in experience, and no amount of architectural tidiness makes it not one. We judged that
  accepting orders while the venue is degraded is worth more than learning the verdict sooner — a
  judgement that would be wrong for a venue-facing UI and is right for a service that owns the order
  record.
- **Latency gained a second polling interval.** Place → outbox poll (≤200 ms) → Kafka → submitter →
  venue. Nothing here is slow, but the floor is higher than a synchronous call's and it is paid on every
  order.
- **Duplicate submissions are now normal traffic.** Every `place()` must be safe to repeat, forever, and
  every future operation added to `ExchangeClient` inherits that obligation. We cannot make this
  conditional on the submitter being careful, because at-least-once is not a bug to be fixed.
- **An order can be stranded in `PENDING` and the API cannot say why.** If the submitter is down, orders
  accumulate in a state that looks identical to "about to be submitted". `submit_stuck_total` and the
  outbox backlog gauge are the only things that distinguish them, and until Phase 4 there is no
  automated resolution — only an alert.
- **One more process to operate.** The submitter joins the outbox publisher as something that can stop
  silently while the API keeps answering 202 cheerfully.
- **Debugging now spans a broker.** "Why was this order not sent" is a question about an outbox row, a
  topic partition, a consumer group offset and a log line, instead of a stack trace. Correlation ids run
  through all of it, which helps and is not the same as a stack trace.

## Consequences

- **`POST /api/v1/orders` returns 202 for `PENDING`, 201 for terminal-in-request.** The existing
  idempotency layer stores the original status code, so a replay returns whatever the first response
  was — including 201 for a replayed rejection. An acceptance test asserts exactly that, because it is
  the case a reader would assume works and nobody would check.
- **The submitter consumes with its own group id**, separate from the Phase 2 verifier, and acts only on
  `ORDER_CREATED`. Other event types are acknowledged without action.
- **`ORDER_SUBMITTED` records the venue's order id** in its payload, so the link between our order and
  theirs is in the event log rather than only in the projection.
- **Retryable and non-retryable failures are distinguished in the adapter, not the submitter.** 5xx and
  connection errors are retryable; 4xx are deterministic refusals and are never retried. The submitter
  sees a typed result, not an HTTP status.
- **The order service still does not know the exchange exists.** The ArchUnit rule from Phase 2 —
  nothing in `order` may depend on the web layer, and the outbox may not depend on `order` — now extends
  to the exchange package: the submitter depends on both, and neither depends on it.
