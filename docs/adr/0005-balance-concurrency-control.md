# ADR-0005 — Concurrency control for derived balances

- **Status:** Accepted
- **Date:** 2026-08-04
- **Phase:** 1

## Context

Balances are derived — `SUM(postings.amount)` for an account — and are deliberately not stored
(SPEC §4 Phase 1). The acceptance criterion is that twenty threads debiting one account concurrently
must never drive it negative and never overdraw.

Deriving the balance creates a problem that storing it would not have. Under Read Committed:

```
T1: SELECT SUM(amount) ... -> 100
T2: SELECT SUM(amount) ... -> 100      (T1 has not committed; nothing to conflict with)
T1: INSERT posting -60                 (checks 100 >= 60, fine)
T2: INSERT posting -60                 (checks 100 >= 60, fine)
    both commit -> balance -20
```

Nothing here is a write conflict. Two inserts of *new* rows do not contend, and there is no row
representing the balance to lock. **The thing that must be serialised does not exist as a row.**

A second consequence shapes every option below: while balances are derived, `balance >= 0`
**cannot be a database constraint**. There is nothing to constrain. The non-negativity rule
necessarily lives in the application, and the only question is what makes that check sound under
concurrency.

## Options considered

### A. Lock the `accounts` row as a mutex — chosen

`SELECT id FROM accounts WHERE id = ? FOR UPDATE`, then compute the balance, then insert postings.
The account row is not modified; it is borrowed as a mutex for its own balance.

**For:** serialises exactly what needs serialising, using a row that already exists and already means
"this account". Works under Read Committed with no retry loop and no application-visible aborts.
Appears in `pg_locks` as a lock on a real row of a real table, so production diagnosis is ordinary
rather than clever. Deadlock is avoidable by construction: an entry touching several accounts locks
them in a deterministic order.

**Against:** it can be forgotten, and forgetting is silent. It serialises all activity on an account,
including deposits that could never overdraw it. And the lock's meaning is a convention rather than a
schema fact — a reader who sees `FOR UPDATE` on a row nobody updates needs the comment to know why.

### B. Transaction-scoped advisory locks

`pg_advisory_xact_lock(namespace, hash(account_id))` before the read-modify-write.

**For:** no row required, so it generalises beyond rows; released automatically; cheap.

**Against:** account ids are UUIDs and the lock space is 64-bit, so unrelated accounts can collide
and block each other — rare, invisible, and load-dependent, the worst combination for debugging.
`pg_locks` shows integers, not accounts. It shares option A's central weakness while being harder to
notice in review, because the lock is attached to nothing a reader would look at.

### C. Balance snapshot row with `CHECK (balance >= 0)`

`UPDATE account_balances SET balance = balance - ? WHERE account_id = ?` — the `UPDATE` takes the row
lock and the `CHECK` enforces non-negativity in one atomic statement, so there is no read-then-write
window at all.

**For:** it appears to remove the race rather than guard it, makes `balance >= 0` a real database
constraint, and doubles as the fix for balance query performance.

**Against, and this is the argument worth keeping:** the apparent advantage — "correctness that does
not depend on anyone remembering anything" — does not survive inspection. Introducing a snapshot
introduces a *new* invariant, `snapshot = SUM(postings)`, and that invariant **is equally impossible
to express as a database constraint**: it spans a row and an aggregate over another table, which is
exactly the shape of thing a `CHECK` cannot see (ADR-0004 hit the same wall). So option C does not
eliminate the dependency on discipline. It **relocates** it — from "remember to take the lock" to
"remember to keep the snapshot in step with the postings" — and charges a second source of truth for
the move. Under option A drift is not merely unlikely; it is **undefined**, because there is only one
place the balance can come from.

This is the standard whiteboard question — "why not just snapshot the balance with a `CHECK`?" — and
the answer is that the constraint you gain is smaller than the invariant you take on.

It also contradicts the specification (SPEC §4 Phase 1 forbids a snapshot column), and it concedes
the interesting claim of this phase — balances derived from an append-only log — before making it.

### D. `SERIALIZABLE` isolation, no explicit locks

**For:** correct by construction, with no lock protocol to forget; PostgreSQL's SSI detects the
read-write dependency between the aggregate scan and the insert and aborts one transaction.

**Against:** every write path needs a retry loop for SQLSTATE 40001, and retry interacts with
idempotency in ways that need their own tests — a retried transaction must not create a second entry.
Under the acceptance test's own conditions (twenty threads, one account) the abort rate is high by
design, so throughput is worst exactly where it is measured.

## Decision

**Option A**, with the discipline made structural rather than remembered.

### 1. One write path, and it always locks

A single ledger repository method is the only way postings are written, and it takes the lock itself.
Callers cannot express the unlocked variant, because no unlocked variant is exposed.

### 2. Lock first, then aggregate — never the reverse

```java
// The account row is a mutex for a value that is NOT stored in it. Order matters and is the
// whole point: acquiring the lock BEFORE reading the balance is what makes the read current.
// Reversing these two statements leaves a stale read and silently reintroduces the overdraft
// race — see the statement-order test in LedgerConcurrencyIT.
jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", ...);
BigDecimal balance = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM postings WHERE account_id = ?", ...);
```

Aggregate-then-lock is the classic form of this bug: both transactions read `100`, then queue
politely for a lock that no longer protects anything.

**A test that actually catches the reversed order.** The obvious test — twenty threads, assert the
balance never goes negative — is the weakest of the three below, because reversed-order code passes
it whenever the scheduler happens not to interleave badly. Instead:

- **Primary: assert statement order, not outcome.** The integration test wraps its `DataSource` in a
  small recording proxy (about thirty lines; no new dependency for one assertion) and asserts that
  within every transaction that inserts a posting, the `FOR UPDATE` on `accounts` appears *before*
  any `SUM(amount)` over `postings`. This fails deterministically on reversed code regardless of
  timing. It is coupled to SQL text, which is a real cost, and it is worth it.
- **Secondary: the twenty-thread overdraft test** from the acceptance criteria, kept for what it is —
  evidence about outcomes, not about ordering.
- **If the text coupling becomes painful**, the fallback is a package-private seam in the repository
  that tests latch on to force the breaking interleaving deterministically. Not chosen now: it puts a
  test hook in production code, which is a worse trade than brittle SQL assertions.

### 3. Multi-account entries lock in ascending account-id order

With a test that two transactions touching the same pair of accounts in opposite logical order do not
deadlock.

### 4. Account creation is a concurrent path of its own

The first write to an account may find no row to lock. Get-or-create under concurrency collides on
`UNIQUE (owner_id, asset, type)`, and the naive repair — catch the duplicate-key error and re-select
— **does not work inside the transaction that hit it**: in PostgreSQL a failed statement aborts the
whole transaction, so every subsequent statement fails until rollback, savepoint or not. The correct
form is:

```sql
INSERT INTO accounts (id, owner_id, asset, type) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING;
SELECT id FROM accounts WHERE owner_id = ? AND asset = ? AND type = ?;
```

`ON CONFLICT DO NOTHING` raises nothing and blocks until the conflicting transaction commits, after
which the `SELECT` sees the winner under Read Committed. Tested with N threads racing to create the
same account: exactly one row exists, every thread returns the same id, and no exception escapes.

### 5. `SERIALIZABLE` kept as a demonstration test

One test runs the same twenty-thread scenario under `SERIALIZABLE` with retries, showing it is also
correct and measurably slower. It costs one test and it is the answer to "why not just use
`SERIALIZABLE`?".

### 6. Boundary enforcement

The ArchUnit rules owed by ADR-0002 carry one for this decision: no code outside the ledger
repository may insert into `postings`. Configuration-level and API-level discipline both fail the
same way — silently — unless something mechanical checks them.

## Consequences

- **The isolation level is part of the design, not an ambient setting.** Lock-then-aggregate is
  correct under Read Committed precisely because each statement takes a fresh snapshot, so the
  aggregate taken after the lock sees the previous holder's committed postings. Under
  `REPEATABLE READ` the same code is **silently wrong**: the transaction's snapshot predates those
  postings, and `SELECT ... FOR UPDATE` raises no serialisation error because the previous holder
  only locked the account row without modifying it. The repository asserts its connection's
  isolation level, and this paragraph is why.
- **Contention is per account.** All activity on one account serialises, including deposits that
  could never overdraw it. Acceptable at Phase 1 scale and measured before it is optimised.
- **Non-negativity remains an application rule.** The database enforces that entries balance
  (ADR-0004) and that postings are append-only; it cannot enforce `balance >= 0` while the balance is
  derived. The lock is what makes the application's check sound, which is why forgetting it is
  serious rather than untidy.
- **A known bottleneck with a planned escape.** If a hot account makes the mutex a real constraint,
  the route is Phase 5's materialised view first, and only then option C behind a reconciled
  invariant. That order matters: correctness, then measurement, then caching.
- **Indexing.** `postings (account_id)` is required for the balance aggregate; without it the
  serialisation cost is hidden behind a sequential scan and the twenty-thread test measures the wrong
  thing.
