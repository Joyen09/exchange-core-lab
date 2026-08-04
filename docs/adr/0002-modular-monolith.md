# ADR-0002 — Modular monolith over microservices

- **Status:** Accepted
- **Date:** 2026-08-04
- **Phase:** 0

## Context

The system has five clearly separable concerns: order lifecycle, exchange transport, ledger,
reconciliation, and risk. The diagram in the specification draws them as boxes with arrows between
them, which is the shape people usually turn into services.

There is a specific temptation here. "Execution service, ledger service, reconciliation service,
each with its own database, communicating over Kafka" sounds like the architecture a trading firm
would run, and a portfolio project is judged partly on whether it looks like real systems.

Two things argue against following that instinct.

First, the correctness properties this project exists to demonstrate are **transactional**. An order
state transition and its outbox row must be written in one transaction — that is the whole point of
the outbox pattern, and it requires them to share a database. A double-entry ledger's invariant is
that every entry's postings sum to zero; enforcing it as a database constraint requires the postings
to be in one place. Splitting these across services would replace an invariant the database can
enforce with a distributed protocol that has to be designed, implemented, and — for honesty —
tested against partition and partial failure. That is a much larger project, and it would
demonstrate distributed systems plumbing rather than the execution semantics that are the subject.

Second, a distributed deployment cannot be validated here. Isolation rules (SPEC §1.2) forbid
deploying to the existing host, and the target is a laptop. A microservice architecture that only
ever runs as five containers on one machine has all the costs and none of the properties.

## Decision

Build a **single deployable Spring Boot application** whose internal boundaries are Java packages,
each with a documented contract:

| Package | Owns | Explicitly does not |
|---|---|---|
| `order` | lifecycle, state machine, idempotency, event log | call the exchange directly |
| `exchange` | REST/WebSocket transport, retries, rate limiting | contain business rules |
| `ledger` | accounts, postings, entries, balance invariants | know what an "order" is |
| `recon` | local vs venue vs ledger comparison, break classification | repair breaks automatically |
| `risk` | limits, kill switch | make strategy decisions |
| `guard` | startup endpoint allowlist | anything else |

Rules that make the boundaries mean something:

1. **Modules communicate through events or explicit interfaces, never by reaching into each other's
   persistence.** The ledger's tables are the ledger's; the order module asks for a posting, it does
   not write one.
2. **The ledger never learns domain vocabulary.** It moves value between accounts. This is the
   strictest boundary in the system and the one that keeps the ledger reusable — including for the
   optional wallet work in Phase 6.
3. **The package skeleton exists from the first commit,** with `package-info.java` stating each
   module's contract before there is any code in it. Boundaries that are drawn after the fact tend
   to be drawn around whatever already happened.

## Consequences

**What this buys**

- The outbox pattern can be demonstrated honestly: state change and event publication in one local
  transaction, with an independent publisher and at-least-once delivery downstream.
- The ledger's zero-sum invariant is a database constraint, not a saga.
- A reader can clone, run one command, and follow a single process end to end.
- Phase boundaries stay verifiable: each phase adds one module and its tests.

**What this costs, honestly**

- **Package boundaries are advisory.** Nothing in the compiler stops `order` from importing
  `ledger` internals. This is the real weakness of the approach. A boundary test (ArchUnit or
  equivalent) would make it enforceable; it is not in Phase 0 because there are no modules to
  enforce between yet. It should be added with Phase 1, and if it is not, this ADR is the record of
  a promise unkept.
- **One deployment unit means one blast radius.** A leak in the exchange adapter takes the ledger
  down with it. In a real venue-facing system that is a genuine argument for separation.
- **No independent scaling.** The exchange adapter is I/O-bound and the ledger is
  contention-bound; they cannot be scaled separately. Virtual threads mitigate the first, not
  the second.
- **The migration path is not free.** Extracting a service later means splitting the schema and
  replacing a transaction with a protocol — the work merely deferred, not avoided. Deferring is
  still right: it is cheaper to split boundaries that have proven themselves than to guess at them
  now.

## Alternatives considered

- **Microservices per module** — rejected above: it would trade the transactional properties this
  project exists to demonstrate for distributed plumbing that cannot be validated on the target
  environment.
- **Spring Modulith** — a genuinely good fit: it verifies module boundaries and documents them. It
  adds a framework-specific concept a reader must know before reading the code, and its main
  benefit (boundary verification) can be had from a handful of ArchUnit rules. Reconsider at
  Phase 3 if manual boundaries start eroding.
- **Gradle multi-module build** — compiler-enforced boundaries, no new runtime concepts, and the
  strongest alternative. Rejected for Phase 0 because it multiplies build files while every module
  is still empty, which trades real clarity now for enforcement that only matters later. If the
  ArchUnit rules above turn out to be insufficient, this is the next step, not Modulith.
