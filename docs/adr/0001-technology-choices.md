# ADR-0001 — Technology choices

- **Status:** Accepted
- **Date:** 2026-08-04
- **Phase:** 0

## Context

This project is a portfolio-grade execution service. Its audience is engineers reading the code and
asking "why this, and why not the obvious alternative?". That makes the selection criteria unusual:

1. The stack must be **conventional enough to be assessed quickly**. Exotic choices cost reviewer
   attention that should go to the order state machine and the ledger.
2. It must **run on a laptop**. The whole stack (JVM + PostgreSQL + broker + Prometheus + Grafana)
   has to start with one command and stay within a few GB of RAM. It must never be deployed onto
   the small VM that hosts a separate live trading system.
3. It must support **honest failure-path testing**. Idempotency, row locking, and reconciliation are
   only meaningfully tested against a real database and a real broker.

## Decision

| Concern | Choice | Reasoning |
|---|---|---|
| Language | Java 21 | Primary language for the target roles. Virtual threads are a natural fit for the I/O-bound exchange adapter in Phase 3 without restructuring around reactive types. |
| Framework | Spring Boot 3.5.16 | Boot 4.1 was available at the time of writing; the 3.x line was pinned deliberately — it is what the ecosystem, most documentation, and most reviewers are on, and this project should not spend its novelty budget on framework version archaeology. Revisit once Phase 5 closes. |
| Database | PostgreSQL 16 | Needs real transactions and `SELECT ... FOR UPDATE` for the ledger's concurrent-debit tests. `DECIMAL(36,18)` gives exact money arithmetic. |
| Messaging | Redpanda | Kafka-compatible API with a single binary and no ZooKeeper/KRaft ceremony. On a laptop it costs a fraction of a Kafka deployment's memory. If it ever needs to be real Kafka, the client code does not change. |
| Migrations | Flyway | Plain versioned SQL. Reviewers can read the schema history without learning a DSL, and the migration order is the audit trail. |
| Testing | JUnit 5 + Testcontainers | Failure-path tests are the point of this project; a mocked database would assert against a fiction. The cost is that the suite needs a Docker daemon and takes tens of seconds instead of milliseconds — accepted deliberately. |
| Observability | Micrometer → Prometheus → Grafana | The default JVM/HTTP instrumentation is free, and Phase 5's custom metrics attach to the same registry. Dashboards ship as JSON in the repository so the stack is inspectable immediately. |
| Build | Gradle (Kotlin DSL) + version catalog | Typed build script, one place (`gradle/libs.versions.toml`) for versions. |
| CI | GitHub Actions | Runs the identical `./gradlew build`, including Testcontainers, on a runner with Docker preinstalled. A green badge is the first thing a reader sees. |

### Startup guard placement

The testnet allowlist check (SPEC §1.4) runs in an `EnvironmentPostProcessor`, not in a
`@Validated @ConfigurationProperties` bean.

The obvious approach is bean validation: it is idiomatic and it reads well. It was rejected because
it fails *late* — after context refresh has begun, after the data source and any eagerly created
clients exist, and it can be sidestepped by a test slice or a context that never binds those
properties. An `EnvironmentPostProcessor` runs immediately after configuration data is merged and
before any bean exists, so "the process cannot continue with a bad endpoint" is a structural fact
rather than a convention. `ExchangeProperties` re-checks the same allowlist in its constructor as
defence in depth, so a programmatically constructed context cannot slip past either.

The cost: environment post-processors are registered through a `META-INF/spring` imports file, which
is less discoverable than an annotation. That is documented in the class itself and in the README.

## Consequences

**Accepted costs**

- Testcontainers makes the test suite dependent on a working Docker daemon — locally and in CI.
- Pinning Spring Boot 3.x means a known future upgrade, deliberately deferred.
- Redpanda is not the broker most production shops run. The Kafka API compatibility is the hedge;
  if it leaks, that is itself worth documenting.
- Deriving balances from postings (Phase 1) rather than caching them will not scale. The trade-off
  is correctness first, with materialised views as the Phase 5 escape hatch.

**Explicitly excluded**

Spring Cloud, service discovery, Kubernetes manifests, and any microservice split. In a portfolio
project, unnecessary infrastructure reads as inexperience rather than sophistication — see
[ADR-0002](0002-modular-monolith.md).

## Alternatives considered

- **Kotlin instead of Java** — better ergonomics, but the target roles assess Java, and idiomatic
  Kotlin would obscure how much of the concurrency and generics work is deliberate.
- **Kafka with KRaft instead of Redpanda** — closer to production reality, materially heavier for a
  local stack. Rejected on the "runs on a laptop" criterion.
- **Liquibase instead of Flyway** — more capable (rollbacks, database-agnostic changelogs), and none
  of that capability is needed here. Plain SQL is easier to review.
- **H2 for tests instead of Testcontainers** — fast, and wrong about exactly the semantics this
  project is trying to demonstrate.
- **Spring Boot 4.1** — newest, but it would make the codebase harder for reviewers to skim and adds
  a compatibility risk to every library in the stack for no demonstrable gain.
