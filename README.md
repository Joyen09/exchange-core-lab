# exchange-core-lab

[![CI](https://github.com/Joyen09/exchange-core-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/Joyen09/exchange-core-lab/actions/workflows/ci.yml)

An order execution service built to demonstrate production-grade backend engineering: an explicit
order state machine, idempotency, transactional outbox, a double-entry ledger, reconciliation, and
observability. It talks to the **Binance Spot Testnet and nothing else**, enforced at startup.

**What this is not:** it contains no trading strategy, no signals, and no backtesting. Profit and
loss is not an output of this project. It is deliberately isolated from any live trading system —
separate repository, separate database, separate network, testnet-only endpoints.

> **Status: Phase 0 of 6 complete** — skeleton, container stack, and the safety guardrails.
> The ledger, order state machine, exchange adapter, reconciler, and dashboards land in Phases 1–5.
> See [Roadmap](#roadmap).

## Architecture

The target design; shaded modules are not implemented yet.

```
                       ┌────────────────────┐
    REST API  ────────►│   Order Service    │      · state machine
                       │  (state machine +  │      · idempotency by client_order_id
                       │     idempotency)   │      · transactional outbox
                       └─────────┬──────────┘
                                 │ outbox
                                 ▼
                       ┌────────────────────┐
                       │      Redpanda      │      at-least-once delivery,
                       └─────────┬──────────┘      consumers deduplicate
                                 │
               ┌─────────────────┼─────────────────┐
               ▼                 ▼                 ▼
      ┌────────────────┐ ┌──────────────┐ ┌────────────────┐
      │    Exchange    │ │    Ledger    │ │   Reconciler   │
      │    Adapter     │ │(double-entry)│ │  (scheduled)   │
      └───────┬────────┘ └──────────────┘ └───────┬────────┘
              │                                   │
              ▼                                   ▼
   Binance Spot Testnet only              Breaks → alert + kill switch
        (allowlisted)                        (never auto-repaired)
```

Every module boundary is a Java package with a documented contract, not a separate service. The
reasoning — and the cost of that choice — is in [ADR-0002](docs/adr/0002-modular-monolith.md).

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
| `order` | order lifecycle, state machine, idempotency | call the exchange directly |
| `exchange` | REST/WebSocket transport, retries, rate limits | contain business rules |
| `ledger` | double-entry postings, balance invariants | know what an "order" is |
| `recon` | local vs venue vs ledger comparison | repair breaks automatically |
| `risk` | limits, kill switch | make strategy decisions |
| `guard` | startup endpoint allowlist | anything else |

Only `guard` and the application skeleton carry code today; the rest are declared package
boundaries with documented contracts.

## Testing

```bash
./gradlew test      # or: make test
```

Integration tests run against a real PostgreSQL through Testcontainers — no in-memory database
substitutes, because the behaviour that matters (transactions, row locks, constraints) is exactly
what an in-memory substitute gets wrong. A Docker daemon is therefore required to run the suite.

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

- [ADR-0001 — Technology choices](docs/adr/0001-technology-choices.md)
- [ADR-0002 — Modular monolith over microservices](docs/adr/0002-modular-monolith.md)
- [ADR-0003 — Guard the exchange namespace, not a list of properties](docs/adr/0003-namespace-wide-endpoint-guard.md)
- [ADR-0004 — Enforcing the zero-sum invariant](docs/adr/0004-zero-sum-enforcement.md)
- [ADR-0005 — Concurrency control for derived balances](docs/adr/0005-balance-concurrency-control.md)

## Roadmap

| Phase | Scope | State |
|---|---|---|
| 0 | Skeleton, compose stack, CI, testnet guardrail | done |
| 1 | Double-entry ledger with balance invariants | next |
| 2 | Order state machine, idempotency, transactional outbox | |
| 3 | Binance Spot Testnet adapter, WebSocket recovery, partial fills | |
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
6. **Single instance assumed.** Nothing here has been designed for horizontal scale-out; the
   outbox publisher in Phase 2 will assume one active writer.
7. **Local development only.** No Kubernetes manifests, no deployment automation, no remote host.
8. **Phase 0 has no business behaviour yet.** The stack starts, migrates, reports health, and
   refuses unsafe endpoints — that is the entirety of what is implemented today.
9. **The isolation scanner is only as fresh as the build's input tracking.** `NoOverrideSwitchTest`
   reads the working tree, which Gradle does not treat as a test input by default — so editing a
   scanned file and re-running the build could report an up-to-date pass without the scanner having
   looked at the change. The `test` task now declares the working tree as an input, which fixes the
   observed case, but the class of problem is inherent to a filesystem-reading test inside a cached
   build: it can always be skipped rather than run. A pre-commit hook or an always-run verification
   task would be a stronger place for this check than a unit test.
10. **Endpoint detection is heuristic.** The guard recognises endpoints by property name or URL
    scheme. A bare host with no scheme under a name that does not read like an endpoint would not be
    checked; a typed `Endpoint` value that cannot be constructed without passing the allowlist is
    the stronger design, deferred to Phase 3 (ADR-0003).

