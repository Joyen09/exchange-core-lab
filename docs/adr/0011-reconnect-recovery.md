# ADR-0011 — Recovering after a disconnect: overlap, cumulative values, and `tradeId`

- **Status:** Accepted
- **Date:** 2026-10-13
- **Phase:** 3

## Context

The User Data Stream delivers `executionReport` events over a WebSocket. When that socket drops —
network, listenKey expiry, a Binance restart, a deploy — **the events that occur while it is down are
not replayed**. There is no offset to resume from and no backlog to drain. The stream simply resumes
with whatever happens next, and the fills that happened in between exist only at the venue.

That is the whole problem. A fill the system never hears about is an order whose `filled_quantity` is
permanently wrong, and a ledger that is permanently short an entry. It does not heal, and nothing
downstream notices, because every subsequent event is internally consistent with the wrong state.

Three decisions follow, and each has a tempting wrong answer.

## Decision 1 — Backfill with a deliberate overlap, not a precise resumption

On every successful reconnect:

```
record disconnectedAt = venue time of the last event received before the drop
GET /api/v3/openOrders                        → compare with every local non-terminal order
  for each local non-terminal order absent from openOrders:
GET /api/v3/order                             → fetch its terminal state
GET /api/v3/myTrades?startTime=disconnectedAt − 5 minutes
apply each trade, deduplicated by (symbol, tradeId)
```

The five-minute rewind is the decision. The precise alternative — ask for trades strictly after
`disconnectedAt` — is more elegant and is wrong, because `disconnectedAt` is not trustworthy enough to
be a boundary:

- It is the venue timestamp of the **last event we received**, which is not the moment the socket
  failed. A TCP connection can be dead for some seconds before anything notices, and every fill in that
  window carries a timestamp after `disconnectedAt` only if the clocks and the ordering cooperate.
- Venue timestamps have millisecond resolution and trades can share one. A strict `>` drops a trade that
  shares a millisecond with the last one seen; a `>=` re-fetches it, which is only safe because of the
  deduplication below — so even the "precise" version depends on dedupe being correct.
- Clock skew between our view of `disconnectedAt` and the venue's `startTime` filter is not
  something we control.

The two failure modes are not symmetric, and that is the entire argument:

| | Cost |
|---|---|
| **Fetch too much** | a few redundant queries; every duplicate is discarded by the `tradeId` check |
| **Fetch too little** | a fill that is never learned, an order whose quantity is permanently wrong, a ledger entry that is never written |

One is wasted work. The other is a permanent accounting error that surfaces, if ever, as a
reconciliation break weeks later. Choosing a boundary that is "exactly right" buys nothing, because the
duplicate-handling machinery has to exist anyway for the live stream — and once it exists, overlapping
is free.

**Recovery and the live stream run at the same time.** The stream is not paused during backfill; both
paths deduplicate on the same key, so a trade arriving twice by two routes is the ordinary case, not a
race to be prevented. What *is* suppressed is premature judgement: the order carries a
`recovering_since` timestamp while backfill is in progress, so Phase 4's reconciler does not report a
difference against a state that is still being assembled. `recovering_since` is a column, not an
`OrderStatus` — it is not a stage of an order's life, and putting it in the enum would pollute the
transition table and the terminal-state logic with something that is not a state at all.

## Decision 2 — The venue's cumulative values are authoritative; the client never adds

`executionReport` carries both the increment (`l`, this fill's quantity) and the running totals
(`z`, cumulative filled quantity; `Z` cumulative quote quantity). The system uses the totals:

```
filled_quantity = z
avg_fill_price  = Z / z        (BigDecimal, explicit scale, HALF_UP; NULL when z = 0)
```

The alternative — accumulate `l` locally — is the natural thing to write and it is wrong in a way that
cannot be fixed by being careful:

- **Local accumulation requires exactly-once delivery**, which nothing provides. A duplicated event adds
  twice; a lost event never adds. Both produce a wrong total that looks perfectly plausible.
- **It requires ordering**, which a reconnect destroys by construction: a backfilled trade and a live
  event can arrive in either order.
- **It has no way to self-correct.** Once the running total is wrong it stays wrong, and every later
  event compounds it.

With cumulative values, **every event carries the full answer**, so delivery order stops mattering and
duplicates become idempotent by arithmetic rather than by bookkeeping. An event whose `z` is *lower*
than what is already recorded is an old event that arrived late: it is discarded and counted
(`venue_stale_event_total`). That one rule replaces an entire class of ordering logic.

This is the central design choice of Phase 3. Out-of-order handling is not solved here so much as
dissolved.

**A consequence for Phase 2's projection.** ADR-0008 established that `orders` is a fold of
`order_events`, and the Phase 2 projector *summed* each event's fill quantity. With cumulative values
the fold becomes "take the last event's `z` and `Z`" — order-independent, and simpler. The property test
from ADR-0008 must keep passing unchanged: 500 random walks, rebuilt from the log, compared with the
stored row field by field. **If it fails, the change has a consequence that was not foreseen and the
work stops there** — the test is not to be adjusted to fit.

If a venue ever omits `Z`, the degraded path is `Σ(l × L)`, accumulated locally, with a comment saying
it is a degradation and why. Binance provides `Z`, so this path is not expected to run.

### Why division needs saying out loud

`Z / z` is exact only when it divides evenly. `BigDecimal.divide` without a scale throws
`ArithmeticException` on a non-terminating decimal — so a perfectly ordinary trade (a third of a unit)
would crash the event handler. The scale and `RoundingMode.HALF_UP` are therefore mandatory, and an
acceptance test uses a price that does not divide evenly, because this is the kind of defect that
appears in production and never in a test written with round numbers.

`z = 0` stores `NULL` for the average, never `0`. Zero is a price; the absence of a price is not.

## Decision 3 — `(symbol, tradeId)` is the deduplication key

Persisted in `processed_trades`, with a unique constraint doing the enforcing.

- **`tradeId` is assigned by the venue**, which is the only party that knows whether two reports describe
  the same fill. Any key we compute ourselves — a hash of quantity, price and timestamp — would merge
  two genuinely distinct fills that happen to be identical, and two identical fills at the same
  millisecond are not unusual in a liquid market.
- **It is stable across delivery routes.** The same trade arriving by WebSocket and by
  `GET /api/v3/myTrades` carries the same `tradeId`, which is precisely what makes the overlapping
  backfill in Decision 1 safe.
- **`symbol` is in the key** because `tradeId` is documented as unique per symbol, not globally. A
  global key would be a silent collision between markets — rare, data-dependent, and invisible.
- **Not the event id, not the order id.** `executionReport` has no stable per-delivery identifier we
  could trust across a reconnect, and one order has many trades.

The unique constraint is the mechanism, not a check-then-insert, for the same reason as everywhere else
in this project: a constraint cannot be forgotten by code written later, and a check-then-insert has a
race that passes every sequential test.

**The ledger uses the same key.** A fill's entry is written with
`idempotency_key = trade:{symbol}:{tradeId}`, so Phase 1's idempotent-write path makes a redelivered
trade a no-op at the accounting layer too, not merely at the order layer. The deduplication is not
implemented twice; it is the same identifier doing the same job in two places.

## What we gave up

- **Recovery costs up to four REST calls per reconnect**, against an API with a weight budget. A flapping
  connection therefore burns rate limit at the worst possible moment — when the venue may already be
  unhealthy. The rate limiter is shared with the submission path, so recovery can throttle new orders.
  We accepted that: being slow to accept orders is recoverable, being wrong about fills is not.
- **The five-minute window is a guess.** Long enough for the disconnect detection gap and clock skew,
  short enough that `myTrades` returns a small page. There is no principled value, and a disconnect
  longer than the window plus the detection gap is handled only because `openOrders` and the per-order
  `GET` catch what `myTrades` misses. That belt-and-braces is why there are three queries and not one.
- **We trust the venue's cumulative values completely.** If Binance ever reports a `z` that is wrong or
  non-monotonic, the system follows it, and the stale-event rule means a *correction downward* would be
  discarded as a late event. This is an accepted single point of trust: there is no second source to
  reconcile against until Phase 4, and inventing a local total to cross-check with would reintroduce
  precisely the accumulator this decision removes.
- **`processed_trades` grows without bound** in Phase 3. It is a dedupe ledger with no retention policy,
  because a trade may be redelivered by a backfill at any later time and "how long is long enough" is
  not answerable yet. Phase 5 can bound it once the recovery windows are understood; until then it is a
  table that only grows, and the README says so.
- **Recovery is per-connection, not per-gap.** Every reconnect runs the full backfill, including a
  reconnect after a two-second blip during which nothing happened. The alternative — deciding whether a
  gap was worth checking — is an optimisation that would have to be right about the thing we just said
  we cannot be right about.
- **`recovering_since` is a flag that something must clear.** If a backfill dies halfway, the order stays
  marked as recovering and Phase 4 will not report on it — a hole that is opened here and closed in
  Phase 4, and is written down so it is not forgotten in between.

## Consequences

- **Every reconnect backfills; none assumes nothing was missed.** The success path and the two-second
  blip take the same route, because an exception for the cases that "obviously" lost nothing is how
  losses happen.
- **listenKey is renewed every 30 minutes** against a 60-minute expiry, with three retries before
  fetching a new key and reconnecting; it is deleted on shutdown. Reconnect backoff is 1s, 2s, 4s, 8s,
  capped at 30s, and never gives up — a venue that is down is not a reason to stop trying, it is a
  reason to keep trying politely.
- **Acceptance criterion 1 is the test of all of this**: twenty scripted disconnects at seed-determined
  points, with at least one order filling completely while the socket is down, and a final comparison of
  every field — status, `filled_quantity`, `avg_fill_price` — against the fake venue's own state. A
  recovery that cannot take an order from `SUBMITTED` to `FILLED` without ever seeing the fills live is
  not a recovery.
- **The same seed must produce the same run.** A chaos test that is not reproducible gets re-run until
  it is green, which converts a defect into a flake.
