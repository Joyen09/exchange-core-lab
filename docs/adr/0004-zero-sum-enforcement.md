# ADR-0004 — Enforcing the zero-sum invariant

- **Status:** Accepted
- **Date:** 2026-08-04
- **Phase:** 1

## Context

Every entry's postings must sum to exactly zero. This is the one invariant that makes a ledger a
ledger: if it can be violated, every balance derived from it is a guess.

The specification requires it be enforced by a database constraint or trigger, not by application
code. The reason is not stylistic. Application enforcement holds only for writes that go through the
application — it does not hold for a migration script, a `psql` session during an incident, a repair
job, or a second service added later. An invariant that a human with a database connection can break
is a convention.

The obstacle is mechanical: an entry's postings are **multiple rows**, and PostgreSQL's row-level
`CHECK` constraint can only see one row. Worse, the natural write pattern inserts postings one at a
time, so the invariant is *legitimately false* between the first insert and the last. Any check that
fires per statement rejects correct code.

## Options considered

### A. Deferred constraint trigger (`DEFERRABLE INITIALLY DEFERRED`) — chosen

The check runs at `COMMIT`, when all of the entry's postings exist. Until then the transaction is
free to build the entry incrementally.

**For:** the invariant lives in the database and applies to every writer, including `psql`; it
permits the natural incremental write pattern; violation aborts the transaction, so there is no
partial state to clean up and no repair path to test.

**Against:** the error arrives at commit, detached from the statement that caused it; PostgreSQL
supports only `FOR EACH ROW` for constraint triggers, so an entry with *k* postings runs the
aggregate *k* times; and it introduces a serious testing trap, described under Consequences.

### B. Insert entries only through a database function, direct writes revoked

`create_entry(idempotency_key, kind, ref_id, postings[])` validates the sum before inserting, in one
statement; `REVOKE INSERT ON postings` makes it the only path.

**For:** the check is immediate, so errors point at the offending call; one statement per entry, so
no redundant aggregates and no deferred-commit surprises; it forces entries to be written atomically
as whole entries, which is arguably the correct domain model.

**Against:** business logic moves into PL/pgSQL, split across two languages and two test harnesses.
More importantly, privilege management becomes load-bearing: if the application role is ever granted
`INSERT` directly — a plausible accident during an incident — the invariant disappears with no error
to notice. **Its failure mode is silent**, which is what ADR-0003 rejected.

### C. Application-level check with a periodic audit

**For:** simplest to write and explain, with the best error messages. The audit job is worth having
regardless of which option wins — it is what would catch a trigger accidentally dropped by a
migration.

**Against:** fails the specification, and fails it exactly where it matters — any writer that does
not go through the service. Detection after the fact is not enforcement: in a ledger, data that is
repaired later has already been read by something.

*(A per-entry aggregate table with `CHECK (total = 0)` does not work: PostgreSQL `CHECK` constraints
cannot be deferred, so the first posting of any entry would fail. That limitation is precisely why
constraint triggers exist.)*

## Decision

**Option A**, implemented as three triggers rather than one. The first is the invariant itself; the
other two close holes that the first cannot see.

### 1. Zero-sum, deferred to commit

```sql
CREATE OR REPLACE FUNCTION assert_entry_balanced() RETURNS trigger AS $$
DECLARE
    target_entry UUID;
    total        NUMERIC(36, 18);
BEGIN
    target_entry := CASE TG_OP WHEN 'DELETE' THEN OLD.entry_id ELSE NEW.entry_id END;

    SELECT COALESCE(SUM(amount), 0) INTO total FROM postings WHERE entry_id = target_entry;

    IF total <> 0 THEN
        RAISE EXCEPTION 'entry % is unbalanced: postings sum to %', target_entry, total
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER postings_entry_balanced
    AFTER INSERT OR UPDATE OR DELETE ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_balanced();

CREATE INDEX postings_entry_id_idx ON postings (entry_id);
```

It covers `UPDATE` and `DELETE` as well as `INSERT` even though trigger 3 forbids both — so that if
trigger 3 is ever dropped, the balance check still runs. SQLSTATE `23514` is chosen so Spring's
exception translator produces `DataIntegrityViolationException` rather than an uncategorised
`SQLException`; the ledger maps that to a domain exception.

### 2. An entry must have at least two postings, deferred to commit

```sql
CREATE CONSTRAINT TRIGGER entries_have_postings
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_has_postings();   -- count(*) >= 2, else 23514
```

Trigger 1 fires per posting row, so **an entry with no postings at all fires nothing**. Zero-sum
holds vacuously — the empty sum is zero — and the entry is meaningless. The failure is silent, which
is the class of hole this project keeps finding and closing.

Two, not one: a single posting can only balance if its amount is zero, which is a meaningless entry
by another route. Two is the floor for double-entry.

### 3. Postings are append-only, enforced immediately

```sql
CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_posting_mutation();     -- always raises, 23514

REVOKE UPDATE, DELETE ON postings FROM exchange_core;
```

Zero-sum is checked once, at the commit that creates the entry. Every subsequent `UPDATE` or
`DELETE` could break it and would never be re-checked for entries not otherwise touched. Append-only
is therefore not a separate stylistic rule — it is what makes the one-time check sound.

Both mechanisms are kept. The trigger applies to every role including the table owner; the `REVOKE`
survives the trigger being disabled. Neither alone covers the other's gap.

### 4. The application pre-flight check is diagnostics, not enforcement

The ledger service rejects an unbalanced entry before writing, purely so the error names the caller
and the amounts. This must carry a comment saying exactly that, because the obvious future
"simplification" is to notice the two checks are redundant and delete the trigger — keeping the
friendly one and losing the one that actually holds.

## Consequences

### The testing trap, and the tests that exist because of it

**A deferred constraint never fires in a test that rolls back.** `@Transactional` test methods — the
default Spring idiom, and what most of this suite will use — roll back at the end, so `COMMIT` never
happens and the trigger never runs. A test written that way passes identically against a correct
trigger, a broken trigger, and no trigger at all. It is the same failure as a repository scanner that
never scans: green, and meaningless.

Three tests follow directly:

1. **The negative test commits for real.** Deliberately write an unbalanced entry — one posting of
   `+100` and nothing else — through a `TransactionTemplate`, and assert that `commit()` raises
   `DataIntegrityViolationException`, then assert the entry and posting are absent afterwards. This
   is the test that proves the trigger exists and fires. It cannot be `@Transactional`.
2. **A companion test documents the trap.** The same unbalanced write inside a rolled-back
   transaction completes without error. Named so the reason is unmissable
   (`unbalancedEntryGoesUndetectedWhenTheTransactionRollsBack_whichIsWhyTheTestAboveCommits`), it
   exists so nobody later "fixes" test 1 by making it transactional like its neighbours.
3. **The empty-entry test** writes an entry with no postings at all and asserts commit is rejected by
   trigger 2 — the hole trigger 1 cannot see.

Append-only is tested separately and is *not* subject to the trap: trigger 3 is an ordinary `BEFORE`
trigger, so `UPDATE` and `DELETE` fail at statement time and a normal `@Transactional` test catches
them. Both statements are covered, plus a test asserting the `REVOKE` is in place.

### Everything else

- **Errors arrive detached from their cause.** The stack trace points at `commit()`, not at the
  insert that unbalanced the entry. The application pre-flight check (4) is what makes the common
  case diagnosable; the trigger message carries the entry id and the actual sum for the rest.
- **Redundant aggregation.** An entry with *k* postings runs the aggregate *k* times at commit. With
  `postings_entry_id_idx` and the expected *k* of 2–4 this is a few index scans over a handful of
  rows. It becomes O(k²) if a future batch settlement writes entries with thousands of postings; the
  escape is a transition-table statement trigger, and the function being replaced is small enough to
  swap without ceremony.
- **The rule is invisible from Java.** A reader must open the migration to know it exists. The ledger
  package documentation points at this ADR.
- **Property-based testing uses jqwik** (decided, not contested): generating thousands of random
  balanced posting sequences and asserting the ledger still balances is the shape of test this
  invariant wants, and jqwik's shrinking makes a counterexample readable. Those tests must commit,
  for the reason above.
