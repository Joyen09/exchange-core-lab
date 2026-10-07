# exchange-core-lab

[![CI](https://github.com/Joyen09/exchange-core-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/Joyen09/exchange-core-lab/actions/workflows/ci.yml)

An order execution service built to demonstrate production-grade backend engineering: an explicit
order state machine, idempotency, transactional outbox, a double-entry ledger, reconciliation, and
observability. It talks to the **Binance Spot Testnet and nothing else**, enforced at startup.

**What this is not:** it contains no trading strategy, no signals, and no backtesting. Profit and
loss is not an output of this project. It is deliberately isolated from any live trading system —
separate repository, separate database, separate network, testnet-only endpoints.

> **Status: Phase 2 of 6 complete** — skeleton, container stack, safety guardrails, the double-entry
> ledger, and the order service: state machine, two layers of idempotency, and a transactional outbox
> published to Redpanda. The exchange adapter, reconciler, and dashboards land in Phases 3–5.
> See [Roadmap](#roadmap).

## Architecture

The target design; bracketed modules are not implemented yet.

```
                       ┌────────────────────┐
    REST API  ────────►│   Order Service    │      · state machine (gate, not suggestion)
                       │  (state machine +  │      · idempotency: HTTP key + client_order_id
                       │     idempotency)   │      · transactional outbox
                       └─────────┬──────────┘
                                 │ outbox (polled, SKIP LOCKED)
                                 ▼
                       ┌────────────────────┐
                       │      Redpanda      │      at-least-once delivery,
                       └─────────┬──────────┘      consumers deduplicate by event_id
                                 │
               ┌─────────────────┼─────────────────┐
               ▼                 ▼                 ▼
      ┌────────────────┐ ┌──────────────┐ ┌────────────────┐
      │  [ Exchange  ] │ │    Ledger    │ │ [ Reconciler ] │
      │  [ Adapter   ] │ │(double-entry)│ │ [ (scheduled)] │
      └───────┬────────┘ └──────────────┘ └───────┬────────┘
              │                                   │
              ▼                                   ▼
   Binance Spot Testnet only              Breaks → alert + kill switch
        (allowlisted)                        (never auto-repaired)
```

Every module boundary is a Java package with a documented contract, not a separate service. The
reasoning — and the cost of that choice — is in [ADR-0002](docs/adr/0002-modular-monolith.md).

## Start here

Three things worth more than a tour of the file tree:

1. **[The testnet guardrail](#safety-guardrails)** and the test that pins it,
   [`GuardRunsBeforeAnyBeanIsCreatedTest`](src/test/java/io/github/joyen09/exchangecore/guard/GuardRunsBeforeAnyBeanIsCreatedTest.java)
   — a control with no override, and the assertion that proves *where* it fires rather than merely
   that it fires.
2. **[The most useful bug in this repository](#the-most-useful-bug-in-this-repository)** — a defect
   that survived a whole phase with every observable symptom correct, and what it changed about how
   the tests here are written.
3. **[The architecture decision records](#architecture-decisions)** — each one has a section on what
   was given up, because an ADR that only lists benefits is not finished.

`make demo`, the one-command walkthrough of a full order lifecycle, arrives in Phase 5.

## Quick start

Requirements: Docker with the Compose plugin. No local JDK needed — the image builds itself.

```bash
git clone https://github.com/Joyen09/exchange-core-lab.git
cd exchange-core-lab
docker compose up -d --build
curl -s http://localhost:18080/health
```

```json
{"status":"UP","components":{"db":{"status":"UP",...},"ping":{"status":"UP"}}}
```

`make help` lists the shortcuts (`make up`, `make test`, `make logs`, `make clean`).

### Ports

Host ports are deliberately non-default so this stack cannot collide with anything already running
on your machine.

| Service | Host port | Purpose |
|---|---|---|
| app | 18080 | `/health`, `/info`, `/prometheus` |
| PostgreSQL | 55432 | application database (`exchange_core_lab`) |
| Redpanda | 19092 | Kafka API |
| Prometheus | 19090 | metrics |
| Grafana | 13000 | dashboards (anonymous viewer access enabled) |

## Safety guardrails

This repository exists alongside a separate live trading system that must never be affected by it.
That constraint drove several decisions that would otherwise look excessive:

- **Testnet allowlist, enforced at startup.** Every endpoint under `exchange.*` is checked against a
  compile-time constant set of Binance Spot Testnet hosts. Anything else aborts the process before a
  single bean — including the data source — is created.
- **Coverage by namespace, not by list.** The guard verifies any property in the exchange namespace
  whose name or value carries a URL, so an endpoint added in a later phase is guarded on the day it
  is introduced rather than when someone remembers. A reflection-driven test generates a case per
  declared endpoint property, so adding one adds its own test — see
  [ADR-0003](docs/adr/0003-namespace-wide-endpoint-guard.md).
- **No override.** There is no property, profile, environment variable, or build flag that relaxes
  the check. `MainnetStartupFailsTest` asserts this by throwing plausible back-door flags at the
  application and confirming it still refuses to start. `NoOverrideSwitchTest` scans the repository
  and fails the build if an override-shaped key ever appears.
- **Exact host matching.** Matching is done on the parsed URI host with exact equality — never
  `contains` or `endsWith`, which would accept `testnet.binance.vision.example.com`, a mainnet URL
  with `testnet` in its query string, or `https://testnet.binance.vision@somewhere-else/`. All of
  those are covered by tests.
- **One normalisation only: ASCII case folding.** Punycode labels, hosts with a trailing root dot,
  percent-encoded separators, and Unicode homoglyphs (Cyrillic `е` in `tеstnet`) are all rejected
  rather than normalised — folding could only ever widen what matches.
- **TLS only.** Plaintext `http`/`ws` schemes are refused, so a transparent proxy cannot be quietly
  interposed between this service and the venue.
- **Credential hygiene.** `.env.example` holds placeholders only, and a test enforces that. Secrets
  arrive through the environment; nothing key-shaped is ever committed.
- **Two layers that catch different callers.** Ledger postings are append-only by database trigger
  *and* by revoked privilege, and the tests show these are not the same control wearing two hats. The
  application connects as a non-superuser role, so it is stopped by `permission denied` and never
  reaches the trigger. Anyone holding the owner credentials — a superuser, who bypasses privilege
  checks entirely — is stopped by the trigger and never reaches the privilege check. Remove either
  layer and one of those callers gets through. That is what "defence in depth" is supposed to mean,
  and it is worth having a case where it can be demonstrated rather than asserted.

Pointing the service at a non-allowlisted endpoint produces this and exit code 1:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

The exchange endpoint configured in 'exchange.rest-base-url' is not an allowed Binance Spot Testnet endpoint.
REFUSING TO START: exchange endpoint is not on the testnet allowlist.

  property        : exchange.rest-base-url
  configured value: <redacted mainnet host>
  reason          : host '<redacted mainnet host>' is not on the testnet allowlist
  allowed hosts   : testnet.binance.vision, stream.testnet.binance.vision
  allowed schemes : https, wss
```

## Modules

| Package | Owns | Explicitly does not |
|---|---|---|
| `order` | order lifecycle, state machine, funds lock | call the exchange directly, write its own SQL |
| `api` | HTTP contract, problem details, correlation ids | contain business rules |
| `idempotency` | request de-duplication and response replay | know what an order is |
| `outbox` | at-least-once delivery of events | know what it is delivering |
| `exchange` | REST/WebSocket transport, retries, rate limits | contain business rules |
| `ledger` | double-entry postings, balance invariants, idempotent writes | know what an "order" is |
| `recon` | local vs venue vs ledger comparison | repair breaks automatically |
| `risk` | limits, kill switch | make strategy decisions |
| `guard` | startup endpoint allowlist | anything else |

`guard`, `ledger`, `order`, `api`, `idempotency` and `outbox` carry code today; `exchange`, `recon`
and `risk` are declared package boundaries with documented contracts. The boundaries are enforced by
ArchUnit rather than convention — the ledger cannot depend on the rest of the system, only a
`*Repository` may reach the database, the domain cannot depend on the web layer, the outbox cannot
depend on the order package, and nothing outside the `exchange` module may construct an HTTP or
WebSocket client. Each of those rules was checked against a deliberate violation to confirm it fails
when it should.

### The ledger

Balances are **derived** by summing an append-only `postings` table; there is no snapshot column, so
an account balance exists in exactly one place. Three invariants are enforced by the database rather
than by application code, because a rule a `psql` session can break is a convention:

| Invariant | Mechanism |
|---|---|
| every entry's postings sum to zero | deferred constraint trigger, checked at commit |
| an entry has at least two postings | deferred constraint trigger — the zero-sum trigger fires per posting row, so an entry with none balances vacuously |
| postings are append-only | immediate trigger, plus `UPDATE`/`DELETE` revoked from the application role |

The application connects as `exchange_core_app`, a non-superuser role that holds `SELECT`/`INSERT`
on postings and nothing else; migrations run as the owner. That separation is what turns the revoked
privileges into a control rather than catalogue decoration — a superuser would bypass them.

Writes serialise on the account row, locked *before* the balance is read — see
[ADR-0005](docs/adr/0005-balance-concurrency-control.md) for why that ordering is the whole design
and how it is tested. Replaying an `idempotency_key` returns the original entry rather than an
error.

### The order service

```bash
curl -s -X POST http://localhost:18080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 11111111-1111-1111-1111-111111111111' \
  -d '{"clientOrderId":"demo-1","symbol":"BTCUSDT","side":"BUY",
       "type":"LIMIT","timeInForce":"GTC","quantity":"0.5","price":"60000"}'
```

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/orders` | requires `Idempotency-Key`; 201 on creation, replayed byte-for-byte on retry |
| `GET` | `/api/v1/orders/{id}` | 404 if unknown |
| `GET` | `/api/v1/orders?symbol=&status=&cursor=&limit=` | keyset pagination over `(created_at, id)`, opaque cursor |
| `DELETE` | `/api/v1/orders/{id}` | 200 if cancelled outright, 202 if a cancel is now in flight |

Amounts are JSON **strings**, in both directions. A JSON number invites the client to parse it into a
`double`, and the point at which that becomes a problem is not the point at which it is noticed.
Errors are RFC 7807 `ProblemDetail` documents with an additional machine-readable `code`.

**The state machine is a gate, not a suggestion.** The legal transitions are a table that a test
enumerates cell by cell, and a refused transition writes *nothing* — no event, no outbox row, no
version bump. Two cases in it are worth reading the comments for: `CANCELING → FILLED` is legal,
because an order can fill while its cancel is still in flight and a state machine that refused
reality would be catching the venue rather than a bug; `PENDING → FILLED` is not, because an order
that was never sent cannot have filled.

**Two layers of idempotency, answering two different questions**
([ADR-0007](docs/adr/0007-two-layer-idempotency.md)):

| Layer | Keyed on | Duplicate it catches | Answer |
|---|---|---|---|
| `Idempotency-Key` header | the request, fingerprinted | the same request sent twice | the original response, replayed exactly |
| `client_order_id` | the order, `UNIQUE (owner_id, client_order_id)` | two requests that would create the same order | 200 with the existing order and a `notice` |

Neither is sufficient alone, and the asymmetry is visible in the shipped behaviour: a retried
cancellation has no `client_order_id` to collide with, and a caller that generates a fresh key after
a crash defeats the header while the constraint still holds.

**The status code describes the request, not the order.** The consequence that looks like a bug until
you have the rule: an order rejected for insufficient funds returns **201 Created**, with
`"status": "REJECTED"` in the body. The request was understood, accepted, and produced a persisted
order and two events. A 4xx would be a claim about the request, and the request was fine.

**The outbox** is written in the same transaction as the state change, so nothing can be published
without being persisted and nothing persisted goes unpublished
([ADR-0006](docs/adr/0006-transactional-outbox-by-polling.md)). A polling publisher claims batches
with `FOR UPDATE SKIP LOCKED`, retries with exponential backoff capped at five minutes, and after ten
attempts sets `dead_at` — a terminal state with a metric, not a row that quietly keeps failing.
Delivery is therefore at-least-once; `order_events` is append-only and is the source of truth, with
`orders` as a projection folded from it ([ADR-0008](docs/adr/0008-order-events-as-the-source-of-truth.md)).

## Testing

```bash
./gradlew test      # or: make test
```

Integration tests run against a real PostgreSQL through Testcontainers — no in-memory database
substitutes, because the behaviour that matters (transactions, row locks, constraints) is exactly
what an in-memory substitute gets wrong. A Docker daemon is therefore required to run the suite.

Several tests are written to fail for the right reason rather than merely to pass:

- **Ordering, not outcome.** The overdraft race is invisible to a twenty-thread test whenever the
  scheduler is kind, so the primary test records the SQL each transaction issues and asserts the
  account lock precedes the balance read. A companion test feeds it the reversed order to prove the
  assertion can fail at all.
- **Commits, not rollbacks.** A deferred constraint is checked at `COMMIT`, so a `@Transactional`
  test would pass against a broken trigger and against no trigger. The zero-sum test commits for
  real, and a deliberately-named neighbour documents why it must.
- **Where, not whether.** The startup guard is asserted to fail *before any bean is created*, with an
  unreachable database present to make the distinction observable. This one exists because its
  absence hid a real defect for a phase — see the correction in
  [ADR-0001](docs/adr/0001-technology-choices.md).
- **Property-based.** 1000 randomly generated entries, asserting the ledger still balances after
  each (jqwik). And 500 random walks of the order state machine, each rebuilt from its event log and
  compared with the stored row by record equality — which found a real defect that every
  hand-written test had missed, because every hand-written test checked the row rather than the log.
  See [ADR-0008](docs/adr/0008-order-events-as-the-source-of-truth.md).
- **Crash windows, not happy paths.** The outbox's hardest case is a publisher that dies *after* the
  broker accepted the message and *before* the database recorded it. A test does exactly that, then
  asserts two deliveries and one effect — which is what at-least-once plus de-duplication buys, and
  is unobservable on a good day.
- **Contention, not sequence.** Fifty simultaneous requests with one `Idempotency-Key`, and fifty
  with distinct keys and one `client_order_id`. A check-then-insert with a race in it passes every
  sequential idempotency test in the suite; these are the two that fail it.

A **verifying consumer** (`OrderEventVerifier`) reads the published topic back and records each
`event_id`. It is not a product feature: it exists so that the delivery guarantees in ADR-0006 are
*tested* rather than asserted, and it is the model Phase 4's reconciliation consumer follows. Its
duplicate and out-of-order counters are what the outbox tests assert on.

## The most useful bug in this repository

The startup guard described above — the one that refuses any non-testnet endpoint *before the
application exists* — **did not run at all for the whole of Phase 0**.

It was registered in `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`.
Spring Boot does not read `.imports` files for that type; that mechanism is for auto-configuration
classes. The post-processor was simply never loaded. What actually rejected mainnet endpoints was a
constructor check on `ExchangeProperties` — a bean, created during context refresh — which is
precisely the late-failing design [ADR-0001](docs/adr/0001-technology-choices.md) records as
considered and **rejected**.

### Why it stayed hidden

Every observable symptom was correct.

| What you could observe | What it implied | What was actually happening |
|---|---|---|
| the process refused to start | the guard rejected the endpoint | a bean constructor did, much later |
| a clear banner named the offending property | the guard's failure analyzer ran | it ran — on an exception thrown from a bean |
| the container exited 1 | fail-fast worked | it did, after the data source had been built |
| CI was green | the behaviour was covered by tests | the tests only asked *whether* startup failed |

There was nothing to notice. The disguise held until Phase 1 added the ledger, which changed bean
creation order so that a database connection failure started winning the race — and the guard's
exception vanished from the output.

### The distinction

> The tests asserted **that** startup failed.
> Nothing asserted **where** it failed.
> Those are two different claims, and only one of them was true.

A test that asserts an outcome will happily accept any mechanism that produces that outcome,
including the one the design explicitly ruled out. The stronger the outcome looks, the less anyone
thinks to check the mechanism.

### What changed

Registration moved to `META-INF/spring.factories`, and the assertions now measure the proposition
instead of a symptom:

- `GuardRunsBeforeAnyBeanIsCreatedTest` records lifecycle events and counts bean instantiations,
  then asserts **no context was ever initialised and not one bean was instantiated**. It was run
  against the original broken registration to confirm it fails there — an assertion never observed
  failing is not yet known to work.
- Its companion asserts the recorder *does* observe context initialisation for an allowed endpoint,
  because a recorder that silently records nothing would make the whole thing unfalsifiable.

The same habit shows up in the ledger tests, which were written after this: the zero-sum test
commits for real because a deferred constraint never fires in a rolled-back transaction, and the
lock-ordering test asserts statement order rather than outcome because the twenty-thread version
passes on reversed code whenever the scheduler is kind. Each carries a deliberate companion whose
job is to prove the main assertion can fail.

### The general form

**An architectural claim that no test can distinguish from its opposite is a claim nobody is
checking.**

The habit that follows is cheap: for every test, ask what change to the production code would make
it fail. If the answer is "nothing I can think of", it is documentation wearing a test's clothes —
and documentation that reports itself as passing is worse than none, because it ends an
investigation that should have continued.

## Development environment notes

The compose file references standard public image names (`postgres:16-alpine`,
`redpandadata/redpanda`, `prom/prometheus`, `grafana/grafana`, `gradle`, `eclipse-temurin`), so on
an ordinary connection `docker compose up --build` works with no extra setup.

Two accommodations exist for restricted networks, neither of which changes the deliverable:

- **Registry mirror.** Where Docker Hub's blob CDN is unreachable, configure a mirror on the
  daemon rather than rewriting image names — for example `{"registry-mirrors":
  ["https://mirror.gcr.io"]}` in `/etc/docker/daemon.json`, then restart the daemon. This project
  was developed in such an environment; the compose file itself stays portable.
- **TLS-inspecting proxies.** `docker/app/ca-certificates/` is copied into the build stage and its
  `*.crt` files are installed into both the OS and JVM trust stores. The directory ships empty, so
  it is a no-op on a normal network; drop your proxy's PEM in there if dependency downloads fail
  with `PKIX path building failed`. Certificates placed there are git-ignored.

## Architecture decisions

The correction in ADR-0001 is the write-up of
[the bug described above](#the-most-useful-bug-in-this-repository).

- [ADR-0001 — Technology choices](docs/adr/0001-technology-choices.md)
- [ADR-0002 — Modular monolith over microservices](docs/adr/0002-modular-monolith.md)
- [ADR-0003 — Guard the exchange namespace, not a list of properties](docs/adr/0003-namespace-wide-endpoint-guard.md)
- [ADR-0004 — Enforcing the zero-sum invariant](docs/adr/0004-zero-sum-enforcement.md)
- [ADR-0005 — Concurrency control for derived balances](docs/adr/0005-balance-concurrency-control.md)
- [ADR-0006 — Publishing events by polling a transactional outbox](docs/adr/0006-transactional-outbox-by-polling.md)
- [ADR-0007 — Two layers of idempotency, and why one is not enough](docs/adr/0007-two-layer-idempotency.md)
- [ADR-0008 — `order_events` is the source of truth; `orders` is a projection](docs/adr/0008-order-events-as-the-source-of-truth.md)

## Roadmap

| Phase | Scope | State |
|---|---|---|
| 0 | Skeleton, compose stack, CI, testnet guardrail | done |
| 1 | Double-entry ledger with balance invariants | done |
| 2 | Order state machine, idempotency, transactional outbox | done |
| 3 | Binance Spot Testnet adapter, WebSocket recovery, partial fills | next |
| 4 | Reconciliation, break classification, risk limits, kill switch | |
| 5 | Metrics, Grafana dashboards, structured logs, `make demo` | |

## Known limitations

Honest list, expanded as the project grows:

1. **No matching engine.** This service routes to a venue; it does not build an order book.
2. **No authentication or authorisation.** The REST API is unprotected and single-tenant. Grafana
   allows anonymous viewers. This is a local laboratory, not a deployable service.
3. **No strategy, signals, or backtesting**, by design — those live outside this repository.
4. **Testnet only, permanently.** There is no supported path to a production venue, and adding one
   would mean removing the guardrail this project is partly built to demonstrate.
5. **Balances are derived, not cached.** Summing postings is correct but does not scale; the
   trade-off and its eventual fix (materialised views) are deferred to Phase 5 on purpose.
6. **Single instance assumed, with one exception.** Nothing here is designed for horizontal
   scale-out — except the outbox publisher, which claims rows with `FOR UPDATE SKIP LOCKED` and is
   therefore safe to run in several instances at once. (An earlier version of this list predicted it
   would assume one active writer. It does not, and a test runs two publishers against forty
   messages to show it.) Everything else still assumes one process.
7. **Local development only.** No Kubernetes manifests, no deployment automation, no remote host.
8. **No venue behind the order service yet.** Orders are accepted, validated, locked against the
   ledger, persisted and published, but nothing submits them anywhere: `PENDING → SUBMITTED` and
   everything downstream of it is driven by tests, not by an exchange. Phase 3 supplies the adapter.
9. **The isolation scanner is only as fresh as the build's input tracking.** `NoOverrideSwitchTest`
   reads the working tree, which Gradle does not treat as a test input by default — so editing a
   scanned file and re-running the build could report an up-to-date pass without the scanner having
   looked at the change. The `test` task now declares the working tree as an input, which fixes the
   observed case, but the class of problem is inherent to a filesystem-reading test inside a cached
   build: it can always be skipped rather than run. A pre-commit hook or an always-run verification
   task would be a stronger place for this check than a unit test.
10. **Balance reads are unlocked and may be stale.** The read path returns a number that is
    display-only; it must never become an input to a write decision, and an ArchUnit rule keeps it
    unreachable from the write package. Summing postings is also O(n) in an account's history — the
    materialised view that fixes it is deferred to Phase 5 on purpose.
11. **The owner role can still bypass the revoked privileges,** because it is a PostgreSQL superuser
    and superusers bypass ACL checks. The application connects as a separate non-superuser role, so
    the revoke is a real control on that path — but anyone holding the owner credentials is stopped
    only by the append-only trigger. Both layers are kept, and tests assert which one catches which
    caller.
12. **`MARKET` orders are accepted by the schema and refused by the API.** The enum and the `CHECK`
    constraint allow `MARKET`, but `POST /api/v1/orders` returns 422
    `MARKET_ORDER_NOT_SUPPORTED_YET` for one. The reason is specific rather than laziness: the funds
    lock has to compute a notional amount up front, and a market order has no price to compute it
    from. Doing it properly needs a reference price and a slippage allowance — both Phase 3 inputs —
    and locking the wrong amount is worse than refusing the order.
13. **There is no HTTP endpoint for `CANCELING → CANCELED`.** A cancel request on a live order
    returns 202 and leaves the order in `CANCELING`; only the venue's confirmation should complete
    it, and the venue arrives in Phase 3. Exposing an endpoint that forces the terminal state would
    let a client declare an order cancelled that the exchange is still working on, which is the one
    lie this state machine exists to prevent. The transition itself is implemented and tested — it
    simply has no caller yet.
14. **Delivery is at-least-once, permanently.** Between the broker accepting a message and the
    database recording that it did, there is a window; a process that dies inside it resends. Every
    consumer must de-duplicate by `event_id`. This is inherent to the design rather than a defect to
    be fixed, and `OrderEventVerifier` exists so the claim is tested rather than asserted.
15. **The projection's agreement with the event log is tested, not constrained.** The schema cannot
    express "`orders` equals the fold of `order_events`", so the guarantee rests on one code path,
    one transaction, an ArchUnit rule and a property test standing in for a constraint that cannot be
    written. That is weaker than a constraint, and `OrderProjector.rebuild` exists for the test
    rather than as a repair tool — if the projection ever did drift, the fix would be a script
    somebody writes under pressure.
16. **`clientOrderId` uniqueness is scoped to an owner, and there is one owner.** The constraint is
    `(owner_id, client_order_id)`, which is the right shape, but with `owner_id` fixed at `'local'`
    and not exposed by the API it is effectively a global namespace today.
17. **The idempotency crash window is shortened, not closed.** A process that dies between committing
    the claim and committing the work leaves a claim that answers 409 until the abandoned-claim sweep
    clears it, five minutes later. No value for that interval is simply correct: shorter risks
    releasing a key while slow work is still running, longer makes a crash more visible to clients.
18. **Endpoint detection is heuristic.** The guard recognises endpoints by property name or URL
    scheme. A bare host with no scheme under a name that does not read like an endpoint would not be
    checked; a typed `Endpoint` value that cannot be constructed without passing the allowlist is
    the stronger design, deferred to Phase 3 (ADR-0003).

