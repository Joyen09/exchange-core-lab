# ADR-0006 — Publishing events by polling a transactional outbox

- **Status:** Accepted
- **Date:** 2026-10-07
- **Phase:** 2

## Context

An order's state change has to do two things that must agree: persist, and be published. SPEC §5.2
requires that no state change is ever published without being persisted, and none is persisted without
eventually being published.

Doing both directly is the dual-write problem, and it has no correct form:

```
tx: INSERT order_events ... COMMIT
    kafka.send(event)              <- process dies here: persisted, never published
```

```
    kafka.send(event)
tx: INSERT order_events ... ROLLBACK   <- published, never persisted; consumers now believe
                                          something that did not happen
```

The second failure is the worse one and it cannot be repaired. A consumer that acted on an event for
a state the system never entered has no way to learn that, and we have no way to retract it. The
asymmetry matters: *late* is recoverable, *false* is not.

So the published record must be written in the same transaction as the state change, which means it
must be written to the same database. What reads it out and sends it is the question this ADR answers.

A second requirement comes from Kafka itself. Per-order ordering must survive, which means every event
for one order must land in one partition — so the partition key is the order id, chosen in the
publisher and asserted in `OutboxPublisherIT.eventsForOneOrderStayInOrder`.

## Options considered

### A. Outbox table, polled by the application — chosen

The state change and a row in `outbox` are written in one transaction. A scheduled publisher claims a
batch with `SELECT ... FOR UPDATE SKIP LOCKED`, sends it, and marks it published.

**For:** the atomicity is a plain database transaction — nothing distributed, nothing to configure, no
second system in the write path. The outbox is a table like any other, so the backlog is a `SELECT`,
a stuck message is visible with `psql`, and a redelivery is a column set to `NULL`. `SKIP LOCKED`
makes the publisher horizontally scalable without coordination: two instances step over each other's
claimed rows instead of blocking or duplicating, which
`OutboxPublisherIT.parallelPublishersDoNotDuplicate` checks with two publishers and forty messages.
Failure handling is ours to write, which is a cost below and a benefit here — exponential backoff and a
`dead_at` terminal state are both things we actually want and would have to fight a framework for.

**Against:** latency is bounded below by the poll interval (200 ms by default), and the poll runs
whether or not there is anything to send. Delivery is at-least-once and cannot be anything else —
between `kafka.send` returning and `UPDATE outbox SET published_at` committing there is a window, and
a process that dies inside it resends. That window is not an implementation flaw to be closed; it is
the same two-systems-one-failure-point problem one level down, and the only honest response is to make
redelivery harmless rather than rare. The publisher is also a component we own and must operate: if it
stops, the system keeps accepting orders and silently stops telling anyone.

### B. Change data capture (Debezium reading the WAL)

A connector tails the PostgreSQL write-ahead log and publishes committed rows.

**For:** no polling, no publisher process in our codebase, and latency measured in milliseconds.
Nothing can be committed and then missed, because the WAL *is* the commit record — there is no
possible gap between "persisted" and "eligible for publication". It scales to volumes that would make
option A's polling cost real.

**Against:** it moves the hardest part of the system outside the repository. Kafka Connect, a
replication slot, and a connector configuration become operational dependencies of correctness, and
their failure modes are specific and unpleasant: a replication slot that nobody consumes does not
drop messages, it retains WAL until the disk fills and the *database* stops. The published schema also
becomes the table schema, so a column rename is a breaking change to every consumer — the mapping from
row to event lives in connector config rather than in code that can be reviewed and tested. For this
project, which exists to demonstrate that the guarantee is understood, outsourcing the guarantee to a
component that is then not written here defeats the purpose. The honest summary is that B is the right
answer at scale and the wrong answer for a lab.

### C. Kafka transactions spanning the database write

Produce inside a Kafka transaction, commit both.

**For:** appears to solve it directly.

**Against:** it does not. Kafka transactions are atomic across Kafka partitions, not across Kafka and
PostgreSQL. Committing two independent transactions in sequence just moves the window — there is still
a point where one has committed and the other has not. A true two-phase commit between the broker and
the database is not available, and would trade this problem for XA's recovery semantics, which is not a
trade anyone has been glad of.

### D. Publish nothing; let consumers poll the database

**For:** no broker, no outbox, no delivery semantics at all.

**Against:** it makes every consumer a client of our schema and our connection pool, which is the
coupling the event stream exists to remove. Not seriously considered; recorded because "do we need
events at all" is a fair question and the answer is in SPEC §5.2's contract with Phase 4.

## Decision

Option A. The outbox row is written in the state-change transaction; a polling publisher sends it.

### What the publisher does, and why each part is there

1. **Claim a batch** — `SELECT ... WHERE published_at IS NULL AND dead_at IS NULL AND next_attempt_at
   <= now ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 100`, inside a transaction. `ORDER BY id` keeps
   one order's events in sequence within a batch; `SKIP LOCKED` is what makes a second publisher useful
   rather than merely harmless.
2. **Send, then mark published** — in that order, and this is the window described above. The
   alternative order (mark first, then send) converts at-least-once into at-most-once, trading
   duplicates for *losses*, which is the one failure the SPEC forbids.
3. **On failure, back off exponentially** — `initial × 2^(attempts-1)`, capped at five minutes, written
   to `next_attempt_at`. The cap matters: without it the tenth retry is hours out and the message is
   effectively lost while appearing merely patient.
4. **After ten attempts, set `dead_at`** — a terminal state, with a metric (`outbox_dead_total`) and
   the row left on the table for whoever investigates.

**Why `dead_at` is a timestamp rather than an `attempts >= 10` predicate.** The two are not the same
statement. "How many times has this failed" and "this will never be sent" are separate facts, and one
column cannot hold both — a threshold means the publisher's claim query has to re-derive a decision on
every poll, and changing `maxAttempts` retroactively resurrects or kills rows that were already
decided. With `dead_at`, the partial index `WHERE published_at IS NULL AND dead_at IS NULL` excludes
dead rows outright, and "dead" means what it says. `OutboxPublisherIT.failuresBackOffThenDie` asserts
that a dead message stays dead even after the broker recovers.

### At-least-once needs a de-duplicating consumer, so one exists

A guarantee the system cannot demonstrate is not a guarantee. `OrderEventVerifier` consumes the topic
and records `event_id` in `processed_events` with a unique constraint; a redelivery is suppressed and
counted (`consumer_duplicates_total`), and an out-of-order arrival is counted rather than quietly
accepted (`consumer_out_of_order_total`). `OutboxPublisherIT.deathBetweenSendAndMarkIsSurvivable`
simulates exactly the crash window — a publisher whose `markPublished` throws after the send succeeded
— and asserts two deliveries and one effect.

This consumer is **not** a product feature and the README says so. It exists so the delivery claims in
this ADR are tested rather than asserted, and it is the model Phase 4's reconciliation consumer
follows.

## What we gave up

- **Latency has a floor.** An event is published between 0 and `poll-interval` after it is committed —
  200 ms by default, worst case. CDC would be milliseconds. For an order service this is invisible next
  to exchange round-trip time, and if it ever is not, the escape is to signal the publisher on commit
  (a `LISTEN/NOTIFY` wake-up) before reaching for CDC.
- **The database does work when nothing is happening.** A poll every 200 ms is five indexed queries a
  second against an empty partial index — cheap, but not free, and it never stops.
- **Duplicates are the consumer's problem, permanently.** Every consumer we or anyone else writes must
  de-duplicate by `event_id`. That obligation does not go away with more careful code on our side; it is
  inherent to at-least-once, and the correct response was to make it explicit and test it rather than
  to hope the window stays shut.
- **We own a process that can stop.** If the publisher dies, orders are still accepted and nothing is
  published — the backlog grows silently unless someone is watching. `outbox_backlog` is a gauge for
  exactly this, and it is the metric to alert on before any other in this phase.
- **Ordering is per partition, which means per order, and nothing more.** There is no global ordering
  across orders and we do not pretend otherwise. A consumer that needs to interleave two orders'
  histories must sort by `occurred_at` itself, and will find ties.

## Consequences

- **The event store and the outbox are separate tables with separate jobs.** `order_events` is the
  append-only history and the source of truth (ADR-0008); `outbox` is a delivery queue that is purged
  after seven days. Collapsing them would tie the history's retention to the broker's.
- **`event_id` is generated once, at write time, in the transaction.** It is the de-duplication key, so
  it cannot be generated at send time — a resend must carry the same id, and it is `UNIQUE` on the
  table to make that structural.
- **The partition key is the order id.** Per-order ordering depends on it entirely, and the single-
  partition topic in tests is not what provides it. Asserted on the sent records, not assumed.
- **Published rows are retained for seven days, then purged.** Long enough to answer "did we send it",
  short enough that the table does not become a second event store with no index for the purpose.
- **A dead letter is a human's problem by design.** There is no automatic resurrection, because a
  message that failed ten times with growing backoff failed for a reason that another attempt will not
  change. Clearing `dead_at` by hand is the documented recovery, and it is deliberately a decision
  rather than a default.
