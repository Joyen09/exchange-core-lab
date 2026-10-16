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

Four things worth more than a tour of the file tree:

1. **[The bug that a document caught and a test never could](docs/adr/0009-fake-venue-and-the-real-endpoint.md#what-the-vendored-reference-confirmed-and-what-it-corrected)**
   — Binance's `executionReport` sets the trade id to `-1` when the event is not a fill. The plan was to
   deduplicate fills on `(symbol, tradeId)`, which would have made the first non-trade event claim a key
   that every later one collided with, and they would have been silently discarded. It would have passed
   every test, because the test double was going to be built from the same assumption and emit the same
   `-1`. Found by reading the vendor's documentation, before any of it was written.
2. **[The testnet guardrail](#safety-guardrails)** and the test that pins it,
   [`GuardRunsBeforeAnyBeanIsCreatedTest`](src/test/java/io/github/joyen09/exchangecore/guard/GuardRunsBeforeAnyBeanIsCreatedTest.java)
   — a control with no override, and the assertion that proves *where* it fires rather than merely
   that it fires.
3. **[The most useful bug in this repository](#the-most-useful-bug-in-this-repository)** — a defect
   that survived a whole phase with every observable symptom correct, and what it changed about how
   the tests here are written.
4. **[The architecture decision records](#architecture-decisions)** — each one has a section on what
   was given up, because an ADR that only lists benefits is not finished. Two ideas run through all of
   them and are written up next to the index: [making the mistake impossible to
   express](#the-design-principle-this-repository-is-built-on), and
   [what counts as evidence](#the-other-principle-what-counts-as-evidence).

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

### The one test that talks to Binance

```bash
export BINANCE_TESTNET_API_KEY=...        # from the Binance Spot Testnet portal
export BINANCE_TESTNET_API_SECRET=...
make testnet-check
```

Everything else in this suite runs offline against a fake venue, because three of Phase 3's four
acceptance criteria cannot be produced on a real exchange — you cannot ask Binance to drop your socket
after the second fill. The cost of that choice is that a misunderstanding of the protocol gets
reproduced in the double and then confirmed by the tests.

So the work is divided ([ADR-0009](docs/adr/0009-fake-venue-and-the-real-endpoint.md)): the fake venue
proves **behaviour**, and this one command proves **protocol** — that the signature algorithm is right,
that the error codes mean what the fixtures say, and that the recorded payloads still match what the
venue sends. It is skipped without credentials and never runs in CI, so a green build says nothing
about the wire format. That is a real gap and it is why the command exists rather than being implied.

Keys belong in a local `.env`. They are not in this repository and have never been in the environment
it was built in — which is also why this particular test has never been executed: see its class comment
before reading its first failure as a protocol difference.

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

## The design principle this repository is built on

> **Don't remember to avoid the mistake. Make the mistake impossible to express.**

Every rule that depends on somebody remembering it is a rule that holds until the day it doesn't, and
the day it doesn't is never the day somebody is looking. The same idea therefore shows up at three
different layers here, deliberately, and it is worth seeing them together rather than meeting each one
as a local quirk:

| Layer | Mistake to prevent | How it is made inexpressible |
|---|---|---|
| **Application startup** | pointing the service at a live exchange | the endpoint allowlist has **no override switch** — no flag, no profile, no environment variable. A non-testnet URL does not start the process, and a test scans the whole repository for any property that could reintroduce one |
| **Database schema** | writing a trade de-duplication row keyed on a trade id of `-1`, which `executionReport` sends for non-fill events, silently swallowing every later event for that symbol | `CHECK (trade_id >= 0)` on `processed_trades`. The application also checks `x == "TRADE"`, but the constraint is what turns a forgotten check from a silent discard into a loud failure |
| **Type signature** | querying `openOrders` without a symbol — four times the request weight, and on the recovery path, at the worst possible moment | `fetchOpenOrders(Symbol symbol)` takes the symbol as a parameter. There is no no-argument overload, so the expensive call is not a thing a caller can accidentally write |

The pattern in each case is the same: a comment saying "don't do X" is replaced by a structure in which
X cannot be written. The ledger follows it too — balances are derived rather than stored, so a stale
snapshot is not a thing that can exist; `order_events` has `UPDATE` and `DELETE` revoked *and* a trigger
that raises, so an edited history is not a thing that can be written.

The cost is real and worth naming: each of these is less flexible than a convention, and the day a
legitimate reason appears to do the forbidden thing, the structure has to be changed rather than
bypassed. That is the trade, and it is taken on purpose — see
[the most useful bug in this repository](#the-most-useful-bug-in-this-repository) for what the
alternative looked like when it failed.

### When a door *is* allowed, and the test for it

A principle with no stated exception gets used in one of two wrong ways: applied mechanically where it
does not belong, or thrown out entirely at the first case where it obviously does not fit. So the
exception is written down.

Not every control here is absolute. `./gradlew test -PskipDockerCheck` bypasses the Docker gate, on
purpose, for someone running only the unit tests. The endpoint allowlist has no equivalent and never
will. The difference is not importance or taste:

> **The cost of a back door is whatever happens on the other side of it.**

Behind the endpoint allowlist is a live exchange with real money. Going through that door is
*irreversible* and the damage is invisible until later. Behind the Docker gate are some tests that
will not run. Going through that door fails immediately, visibly, and costs a re-run.

So the test for an escape hatch is: **if someone takes it by mistake, do they find out straight away,
and can they undo it?** Two yeses, a flag is fine. One no, make it impossible to express. The
guardrail this project is partly built to demonstrate is a two-no case, which is why it has no switch,
no profile and no environment variable, and why a test scans the repository for anything that could
become one.

## The other principle: what counts as evidence

The section above is about **prevention** — how the system is built so a mistake cannot be written.
This one is about **verification**, and it is a separate idea that happens to have come up just as
often: how do you know a thing you believe is actually true?

The cleanest specimen first, because it takes one sentence:

> A probe edited a file with `sed`, the delimiter collided with a `|` in the replacement, `sed` exited
> with an error, the file was never modified — **and the test passed.** Read as evidence, that green
> meant "the rule holds against a deliberate violation". It meant the violation had not happened.
>
> **You can believe you are testing something while testing a change that was never made.**

That is the whole pattern, and it has turned up seven times here:

| What happened | What looked like evidence | What it actually established |
|---|---|---|
| a `sed` probe failed silently | the test passed, so "the rule catches violations" | that nothing had been violated |
| a build gate stopped the run | `build/test-results` said 299 tests, 0 failures | that the *previous* run had passed; nothing ran this time |
| `executionReport` sets `t` to `-1` for non-fill events | a protocol constant everyone had reasoned their way to | that nobody had read the primary source |
| the `-2010` error table | an error table whose contents matched | that a copy under `/products/wallet/` agrees by coincidence, not by authority |
| the V5 migration's validation pass | "it ran without aborting" | possibly that it was checking the wrong thing |
| the migration's abort message | a green `hasMessageContaining` assertion | that one fragment of the message matched — the count in it was wrong, and nobody saw |
| `firstArrival < System.nanoTime()` | a passing assertion about event ordering | nothing: it is true whenever any event arrived. The first one here found by reading a diff rather than by running something |

Those two are a pair, and between them they cover the ways a green can lie: the first is **a change
that never happened**, the second is **a run that never happened**. Neither leaves a mark. The rest of
the table is the same shape with more words:

**a check passes, and what it checked is not the claim being made.**
The guard-registration bug in [the section below](#the-most-useful-bug-in-this-repository) is the same
shape and the most expensive instance of it — a test asserted *that* startup failed, nobody asserted
*where*, and the two are different claims.

What this has turned into, in practice:

- **A negative control for anything that is supposed to catch something.** Before trusting a rule, run
  the case it is meant to reject against the *old* rule and confirm it passes there. V5's probe did
  exactly this and is written up in [ADR-0004](docs/adr/0004-zero-sum-enforcement.md); without it, the
  new test would have been green against both rules and measured nothing.
- **A test that something _can_ happen needs a partner showing it does not _always_ happen.** The two
  that exist here were each a line away from being decorative:

  | The claim | Its partner | What the partner rules out |
  |---|---|---|
  | the same seed produces the same event sequence | a *different* seed produces a different one | a script that ignores the seed entirely |
  | the venue can deliver the execution report before the REST response | by default it delivers the response first | a venue that always sends the report first, which is unrealistic in a new direction |

  This is the negative control's twin. The first asks *can this test fail?*; this asks *is the thing it
  observed ever absent?* A capability that is always on is not a capability, and a test asserting one
  is measuring a constant.
- **Assert the value, not a substring of the message it appears in.** The migration reports the number
  of unbalanced entry/asset combinations. A `hasMessageContaining` check let a wrong count through, so
  the count is now asserted as a number — and a view, `unbalanced_entry_assets`, makes it queryable
  rather than only printable.
- **Cite the source, and cite the right one.** Protocol constants live in
  [`docs/vendor/binance-citations.md`](docs/vendor/binance-citations.md) with document paths, line
  numbers and a retrieval date. A constant with no entry there is a guess, and the comment beside it
  says so.

The two principles answer different questions. The first is *how is this built*; this one is *how do
you know it works*. They are kept apart here because conflating them produces the most comfortable
failure mode in engineering: a well-designed system that nobody has actually checked.

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
- [ADR-0009 — A fake venue for behaviour, the real testnet for protocol](docs/adr/0009-fake-venue-and-the-real-endpoint.md)
- [ADR-0010 — Orders reach the venue through an event, not through the request](docs/adr/0010-event-driven-order-submission.md)
- [ADR-0011 — Recovering after a disconnect: overlap, cumulative values, and `tradeId`](docs/adr/0011-reconnect-recovery.md)

ADR-0004 and ADR-0007 carry Phase 3 corrections: the zero-sum invariant was only correct for
single-asset entries, and a successful placement now returns 202 rather than 201.

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
   trade-off and its eventual fix (materialised views) are deferred to Phase 5 on purpose. The
   `unbalanced_entry_assets` view is the same debt in a second place: it joins and groups the whole
   `postings` table with no `WHERE`, and Phase 4's reconciler will query it on a timer rather than for
   one entry at a time. Both consumers depend on the same derivation, and both are fixed together by
   the materialised view in Phase 5 — noted now rather than discovered then.
6. **Single instance assumed, with one exception.** Nothing here is designed for horizontal
   scale-out — except the outbox publisher, which claims rows with `FOR UPDATE SKIP LOCKED` and is
   therefore safe to run in several instances at once. (An earlier version of this list predicted it
   would assume one active writer. It does not, and a test runs two publishers against forty
   messages to show it.) Everything else still assumes one process.
7. **Local development only.** No Kubernetes manifests, no deployment automation, no remote host. The test
   suite also needs a Docker daemon that stays up: it crashed six times while this phase was being
   built, each time turning the whole integration suite red at once — around seventy failures, all of
   them `DockerClientProviderStrategy` unable to find a strategy rather than anything about the code.
   The build now checks for a reachable daemon before any test runs and stops with one message saying
   so, rather than letting every Testcontainers test fail separately; `-PskipDockerCheck` runs the unit
   tests without one. Restarting the daemon and re-running is the whole fix.
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

