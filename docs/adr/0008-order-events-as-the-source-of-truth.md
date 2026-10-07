# ADR-0008 — `order_events` is the source of truth; `orders` is a projection

- **Status:** Accepted
- **Date:** 2026-10-07
- **Phase:** 2

## Context

An order has a current state and a history, and the two are not independent: the state is what the
history adds up to. The question is which of them the system stores as fact and which it derives.

The requirement that forces the issue is SPEC §5.2: every state change must be recorded, and the record
must be immutable. "Why is this order `REJECTED`" has to be answerable after the fact, and a row that
has been overwritten four times cannot answer it. So the history exists either way — it is not optional,
and this is not a decision about whether to keep an audit log.

What follows is a decision about which one the other has to agree with, and what happens when they
disagree.

## Options considered

### A. `orders` is the truth, `order_events` is an audit log — rejected

Update the row; append a log line beside it.

**For:** the obvious shape, the one every ORM assumes, and the fastest read path — current state is one
indexed row.

**Against:** the log is decorative. Nothing checks that it agrees with the row, so when they disagree —
a code path that updated the row and forgot the event, a migration that touched the row directly, a
`version` that was bumped without a corresponding entry — the log is wrong and *nothing notices*. The
usual mitigation is a code review convention ("always write both"), and ADR-0005 already took a position
on conventions that must be remembered: they are the kind of invariant that holds until the day it
doesn't. Worse, there is no mechanical test that can distinguish a system where the audit log is
trustworthy from one where it has silently drifted, which is the specific failure the Phase 0 guard
incident taught us to treat as the real risk.

### B. Full event sourcing: no `orders` table at all

Rebuild state from events on every read.

**For:** one place where truth lives, and no possibility of divergence because there is nothing to
diverge.

**Against:** every read becomes an aggregate over a growing log, which makes `GET /orders?status=OPEN`
either a sequential scan or a snapshot table — and a snapshot table is option C with extra steps and no
constraints. Keyset pagination over `(created_at, id)` needs an index on columns that would not exist.
It also makes a Phase 3 that polls the exchange for open orders considerably more expensive than it has
any reason to be. Full event sourcing is a real answer to a problem this project does not have.

### C. `order_events` is the truth, `orders` is a projection maintained in the same transaction — chosen

Append the event; update the projection; both or neither, in one transaction.

**For:** the history is the record and cannot be edited — `REVOKE UPDATE, DELETE ON order_events` plus a
`BEFORE UPDATE OR DELETE` trigger that raises, so even the owning role cannot quietly rewrite it. Reads
stay cheap because the projection is a real table with real indexes. And crucially, the claim is
*testable*: if `orders` is derived from `order_events`, then folding the log must reproduce the row, and
that is a property, not an opinion.

**Against:** the projection can drift in the window where code is wrong, and "both in one transaction"
is a property of the one method that does it rather than of the schema. It is more moving parts than A,
and it requires the fold to be written and kept correct — a second implementation of the state
transitions, in effect, which is the cost described below.

## Decision

Option C. `order_events` is append-only and authoritative; `orders` is a projection written in the same
transaction.

### The claim is enforced as a property test, not asserted in prose

`OrderProjectionProperties` generates 500 random walks of the real transition graph, replays each one
through the service, then rebuilds the order from its event log alone and compares with **record
equality** — every field at once, including the ones it would be easy to forget: `version`,
`updatedAt`, `avgFillPrice`.

The generator walks the *real* `OrderStatusTransitions` table to choose each step, so it can only
produce histories the system would actually permit. A generator that produced illegal sequences would be
exercising the rejection path by accident and the projection not at all.

**This test found a real defect, which is the argument for writing it.** The generic `transition()`
method took a `Fill` and updated the projection's `filledQuantity` and `avgFillPrice` from it, but wrote
only the event *type* and the caller's payload to the log — so the fill amounts existed in the row and
not in the history. Every hand-written test passed, because every hand-written test checked the row. The
property test failed on a partially-filled order: stored `filledQuantity = 1`, rebuilt `0`. The fix was
to write the fill into the event payload where it belongs:

```java
if (fill != null) {
    // Written here rather than left to the caller. The projector folds the log from these two
    // fields, so an event that omitted them would rebuild a different order.
    fullPayload.put("fillQuantity", fill.quantity().toPlainString());
    fullPayload.put("fillPrice", fill.price().toPlainString());
    ...
}
```

The defect is worth recording precisely because of how benign it looked. The row was right, every API
response was right, and the event log — the thing this ADR calls the source of truth — was missing the
only data that made a fill a fill. An audit log nobody folds is an audit log nobody has checked.

### Every transition takes the same six steps, in the same order

`OrderService.transitionLocked` is the one path (SPEC §2.3):

1. **`SELECT ... FOR UPDATE`** on the order row — the row is the mutex for its own state machine, for the
   same reason the account row is in ADR-0005.
2. **Verify against the transition table** — a refused transition increments
   `order_transition_rejected_total` and throws, and `OrderTransitionIT` asserts that it writes
   *nothing*: no event, no projection change, no outbox row. "Refused" has to mean refused, not
   refused-after-a-side-effect.
3. **Append the event** with the next `sequence_no` — `UNIQUE (order_id, sequence_no)` makes the
   numbering dense and gapless rather than merely increasing, which is what lets a consumer detect a
   missing event rather than only an out-of-order one.
4. **Update the projection**, including `version`, which is `sequence_no - 1` and therefore derived from
   the log like everything else.
5. **Append the outbox row** (ADR-0006) — same transaction, so publication cannot outlive a rollback.
6. **Apply the ledger effect** — release locked funds on `CANCELED` / `EXPIRED`.

Steps 3–6 are in one transaction. Step 2 before step 3 is what makes the state machine a gate rather
than a suggestion.

### Insufficient funds must leave a persisted `REJECTED` order, which needs a savepoint

This is the one place where the "all in one transaction" shape does not work, and the reason is specific
to Spring and PostgreSQL rather than to the design.

When a funds lock fails, we want an order that exists, with status `REJECTED`, and two events
(`ORDER_CREATED`, `ORDER_REJECTED`) — because the attempt happened and the record of it is the point. But
the funds lock throws `InsufficientBalanceException` from inside the ledger, and if that work ran in a
participating `REQUIRED` transaction, catching the exception would not help: Spring has already marked
the transaction rollback-only, so the outer commit fails with `UnexpectedRollbackException` and the
rejected order is not persisted after all. Catching an exception does not un-mark a transaction.

The funds lock therefore runs in a `PROPAGATION_NESTED` transaction — a JDBC savepoint — so its failure
rolls back to the savepoint and leaves the enclosing transaction usable:

```java
// A savepoint, not a nested transaction: the enclosing transaction survives this failing, which is
// the only reason a REJECTED order can be persisted at all.
savepointTransactions.executeWithoutResult(status -> ledger.lock(...));
```

`DataSourceTransactionManager.setNestedTransactionAllowed(true)` is required for this and is set
explicitly in `ApplicationConfiguration`, with a comment, because it is off by default and the failure
without it is a confusing exception rather than a missing feature. `OrderFundsIT` asserts the whole
outcome: the order is persisted as `REJECTED`, both events exist, and the ledger is untouched — no
postings, no partial lock.

## What we gave up

- **A second implementation of the state transitions.** `OrderProjector.rebuild` folds the log, and the
  service applies the same arithmetic forward. They must agree, and only the property test makes them —
  nothing structural stops them drifting. This is the real price of option C, and it is paid every time a
  field is added to `orders`: the fold has to learn about it, and the property test is what says so.
- **Decimal scale had to be normalised for the comparison to work at all.** `BigDecimal.equals` compares
  scale, so `1.0` and `1.000000000000000000` are unequal records for the same order. The projector
  normalises to scale 18 to match `NUMERIC(36,18)`. That is a small ugliness in service of record
  equality, and record equality is what makes the test check every field rather than the fields someone
  thought to list.
- **Writes cost two statements and a row more than they would.** Every transition writes an event, a
  projection update and an outbox row where option A would write one update. At this scale it does not
  matter and it has not been measured; the honest statement is that it is a real multiplier on write
  cost, accepted for a guarantee we can demonstrate.
- **The projection is correct by construction only as far as one method is.** The schema cannot express
  "`orders` agrees with `order_events`" as a constraint, so the guarantee is: one code path, one
  transaction, an ArchUnit rule confining SQL to the repository, and a property test. That is four
  mechanisms standing in for a constraint we cannot write, and it is weaker than a constraint.
- **There is no rebuild-from-log operation in production.** `rebuild` exists for the test, not as a
  repair tool. If the projection ever did drift, the fix would be a script someone writes under
  pressure. A deliberate `POST /admin/orders/{id}/rebuild` was considered and left out of Phase 2 as
  scope: the first version of a repair tool is more likely to cause the incident than to fix it.
- **`version` means "how many events have happened", not "optimistic lock token".** Concurrency control
  is the row lock in step 1, not a compare-and-set on `version`. The column is there for consumers that
  want to order or de-duplicate, and naming it `version` invites a reader to assume optimistic locking
  that is not there. Kept because it is the conventional name in the event envelope, and documented here
  because the assumption is easy to make.

## Consequences

- **`order_events` cannot be modified, by anyone.** The trigger raises on `UPDATE` and `DELETE`, and the
  application role has only `SELECT, INSERT`. Two layers, because they stop different callers: the grant
  stops the application, the trigger stops anything connecting as the owner — including a migration and
  including a person with `psql`. ADR-0004's reasoning about deferred constraints applies to the trigger:
  it never fires in a test that rolls back, so the negative tests commit.
- **`sequence_no` is dense and starts at 1.** Asserted in `OrderTransitionIT`, and relied on by the
  consumer's out-of-order detection. A gap would be a defect, not a quirk.
- **Terminal states are terminal in the table, not in a comment.** `FILLED`, `CANCELED`, `REJECTED` and
  `EXPIRED` have empty target sets, so a transition out of them is refused by the same gate as any other
  illegal transition.
- **The projection's `updatedAt` comes from the event, not from `now()`.** Otherwise a rebuild would
  produce a different row every time it ran, and the property test would be unwritable — which is a
  useful sign that the dependency was in the wrong direction.
- **Phase 3 inherits the transition gate for free.** The exchange adapter will drive `SUBMITTED`,
  `PARTIALLY_FILLED` and `FILLED` through the same method, so an exchange report that implies an
  impossible transition is refused and counted rather than applied. That is the main reason the gate is
  in the service and not in the controller.
