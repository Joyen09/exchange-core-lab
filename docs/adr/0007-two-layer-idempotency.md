# ADR-0007 — Two layers of idempotency, and why one is not enough

- **Status:** Accepted
- **Date:** 2026-10-07
- **Phase:** 2

## Context

A client that submits an order and loses the connection does not know whether the order exists. Its
only sane move is to retry, and the service's only acceptable behaviour is that the retry does not place
a second order. SPEC §5.4 requires this; SPEC §3.3 fixes the header as `Idempotency-Key` and the
concurrent-duplicate response as 409.

There are two different duplicates here, and conflating them is the mistake this ADR exists to avoid:

1. **The same request, sent twice.** A retry after a timeout, a proxy replaying, a user double-clicking.
   The client believes it is sending one request. The correct answer is *the same response again* —
   including the same status code and the same body.
2. **Two different requests that would create the same order.** Two services both decide to place
   `clientOrderId = ABC`, or one service restarts and re-issues from a persisted intent with a fresh
   request id. These are genuinely distinct requests. The correct answer is *not* a replay — it is the
   order that already exists, plus the fact that it already existed.

A single mechanism cannot answer both, because the questions differ in what counts as "the same". The
first is about the request; the second is about the order.

## Options considered

### A. `Idempotency-Key` only

Record the key, replay the stored response.

**For:** one mechanism, one table, familiar to anyone who has used Stripe's API.

**Against:** it is defeated by the caller generating a new key, which is the normal case for duplicate
*intent* rather than duplicate *delivery*. A service that persists "place ABC" and crashes before
recording the response will retry with a new key — correctly, by its own lights, because as far as it
knows nothing was sent. Layer one waves it through and a second order exists for one intent. The key
protects against a lost response; it cannot protect against a forgotten one.

### B. `clientOrderId` uniqueness only

A unique index on `(owner_id, client_order_id)`; the second insert loses and gets the existing order.

**For:** the strongest guarantee of the two, and the one enforced by the database rather than by code:
there is no interleaving in which two orders exist for one `clientOrderId`.

**Against:** it cannot replay a response, so a retried request gets a *different* answer from the
original — 200 where the first was 201, and a body describing an order that may have moved on since.
Worse, it only protects what the index covers. A cancellation carries no `clientOrderId`, so a retried
`DELETE` has nothing to collide with; neither does any request whose effect is not the creation of a
uniquely-named row. Layer two is the right shape for creation and no shape at all for everything else.

### C. Both — chosen

Layer one at the HTTP boundary, keyed on the request. Layer two in the domain, keyed on the order.

**For:** each answers the question it is actually about, and the two failure modes above are both
covered. The layers are independent: layer two holds even for a caller that ignores the header
entirely, and layer one holds for operations that have no natural domain key.

**Against:** two mechanisms, two tables, two sets of semantics for a reader to hold at once, and a
genuine design question about what the second layer should *return* (§"The notice", below). It is more
machinery than a single-client service needs.

### D. Both, but make layer two a pre-flight `SELECT`

Check for an existing `clientOrderId`, then insert.

**Against:** this is check-then-act with a race in the middle, and the race is not theoretical — it is
exactly what `OrderConcurrencyIT.fiftyKeysOneClientOrderId` fires fifty threads at. It passes every
sequential test. Rejected on the same grounds as ADR-0005 §2: a constraint the database enforces cannot
be forgotten, and a check the application performs can be defeated by timing nobody can reproduce on
request.

## Decision

Both layers. Layer one is `idempotency_keys`; layer two is `UNIQUE (owner_id, client_order_id)` plus
`INSERT ... ON CONFLICT DO NOTHING`.

### Layer one: the claim commits before the work

This is the part that is easy to get subtly wrong, and the ordering is the whole decision:

```
tx1 (REQUIRES_NEW): INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING   COMMIT
tx2:                place the order; UPDATE idempotency_keys SET response_body ...   COMMIT
```

If the claim were part of the work's transaction, a concurrent duplicate's `ON CONFLICT DO NOTHING`
would **block** on the uncommitted row until the first request finished, and then see it and replay.
The client would wait instead of being told anything. That is not a disaster, but it means 409 is
unreachable: nobody can observe a claim that has not committed, so "your request is in flight" is not a
state the API can ever report. SPEC §3.3 asks for 409, and committing the claim first is what makes 409
true rather than decorative. `OrderConcurrencyIT.oneKeyFiftyCallers` asserts that some of fifty
simultaneous callers actually receive it.

The cost is a compensating delete, and it is a real cost:

- **If the work fails**, the claim is released in its own transaction (the work's is already rolling
  back), or the key would answer 409 for its entire 24-hour retention.
- **If the process dies between the two commits**, nothing runs the compensation. That window cannot be
  closed — it is the same two-commits problem as ADR-0006, and the same answer applies: make the
  residue harmless rather than impossible. `deleteAbandoned` clears claims with no completion after
  five minutes, so the worst case for a crashed request is a few minutes of 409 rather than a day.

**No application-level lock anywhere.** A `synchronized` block or a local lock would be wrong rather
than merely insufficient: it would appear to work in a single instance and stop working silently the
moment a second one exists, which is the worst available failure mode. The correctness rests on the
unique index.

### The fingerprint, and what "the same request" means

A replay is only legitimate if the request really is the same one, so the key is stored with a SHA-256
of a canonical form of the body: keys sorted, insignificant whitespace removed, and numeric fields
normalised so `"0.50"` and `"0.5"` are one request.

**Normalisation is driven by field name, not by whether a value parses as a number.** This is
deliberate and the comment in `CanonicalRequest` says why: `clientOrderId` `"001"` and `"1"` are
different identifiers and must stay different, while `quantity` `"0.50"` and `"0.5"` are the same
amount. A value-sniffing normaliser would quietly merge two distinct orders into one. The set of
numeric fields is therefore explicit and small.

A key presented with a different fingerprint is **422 `IDEMPOTENCY_KEY_REUSED`**, not a replay. The
client has a bug — it is reusing a key for a different request — and silently returning the first
order would hide it.

### The notice: layer two returns 200, not 201

When `clientOrderId` already exists, the caller is given the existing order with **200** and a `notice`
object naming `DUPLICATE_CLIENT_ORDER_ID`. Not 201, because nothing was created; not 409, because
nothing is wrong. The caller asked for an order to exist with that id, and it does.

This follows the rule the whole HTTP layer is written to:

> **The status code describes what happened to the request, not what state the order is in.**

The sharpest consequence of that rule is elsewhere and worth stating here because it looks like a bug
until the rule is in hand: an order rejected for insufficient funds returns **201 Created**. The
request succeeded — it was understood, accepted, and produced a persisted order and two events. The
order's status is `REJECTED`, and that is in the body, which is where the order's state belongs. A 4xx
would be a claim about the request, and the request was fine. `OrderApiIT` names this test *"not enough
money is still a 201 — the request succeeded, the order did not"* so the next reader meets the
reasoning before the surprise.

## What we gave up

- **Two mechanisms to understand instead of one.** A reader has to know that two different duplicates
  get two different answers, and that 200-with-notice and 201-replayed are not the same event. The
  README and the test names carry that explanation, which is an ongoing cost of the choice.
- **A window that cannot be closed, only shortened.** A process that dies between the claim commit and
  the work commit leaves a claim that answers 409 until `deleteAbandoned` sweeps it. Five minutes is a
  guess at the right trade: shorter risks releasing a key while slow work is still running (which would
  permit a genuine duplicate), longer makes a crash more visible to clients. There is no value that is
  simply correct.
- **Replay fidelity costs a stored response body.** Every creating request stores its full response for
  24 hours. That is why the column is `TEXT` and not `JSONB` — a replay must return the same bytes, and
  `jsonb` reorders keys and rewrites whitespace, so a round trip through it returns an equivalent
  document rather than the same one. `OrderApiIT.replayIsIdentical` asserts byte equality and failed
  against `JSONB` when the column was first written that way.
- **The domain layer answers a question the HTTP layer already asked.** A retried request with the same
  key never reaches the `ON CONFLICT`, so layer two's work is redundant in the common case. That
  redundancy is the point — it is what remains when the client's key handling is wrong — but it is
  still a second check on the hot path.
- **`clientOrderId` is scoped to an owner, and the owner is `'local'` for now.** The unique index is
  `(owner_id, client_order_id)`, which is the right shape for multi-tenancy, but with one owner it is
  effectively a global namespace. A real deployment must confirm that callers understand the scope is
  per owner and not per connection.

## Consequences

- **The `Idempotency-Key` header is required on every state-changing request.** Absent is **400
  `IDEMPOTENCY_KEY_REQUIRED`**, not a silently non-idempotent write. Making it optional would mean the
  guarantee holds only for clients who happened to opt in.
- **Keys are retained for 24 hours.** Long enough for any retry policy worth honouring, including a
  human retrying the next morning; short enough that the table stays small and a replayed response is
  never so old that returning it would be stranger than rejecting it.
- **Layer two is a database constraint, so it is not optional for future code.** A Phase 3 path that
  creates orders from exchange callbacks gets the same protection without being written to ask for it.
- **The controller fingerprints the raw body, so it takes `@RequestBody String`.** Parsing and then
  re-serialising would fingerprint our rendering of the request rather than the request, and the
  canonical form would then depend on Jackson's configuration — a dependency nobody would expect to
  find between an upgrade and a 422.
- **Cancellation is covered by layer one only**, because it has no `clientOrderId`. This is the
  asymmetry that makes option B insufficient, visible in the shipped behaviour rather than only in this
  document.
