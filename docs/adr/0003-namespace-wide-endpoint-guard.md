# ADR-0003 — Guard the exchange namespace, not a list of properties

- **Status:** Accepted
- **Date:** 2026-08-04
- **Phase:** 0
- **Supersedes:** the property enumeration introduced with the guard in Phase 0

## Context

The Phase 0 startup guard verified two named properties: `exchange.rest-base-url` and
`exchange.ws-base-url`. It was correct, and it was structurally fragile.

The threat it defends against is not a developer deciding to point the service at a production
venue. It is a developer adding `exchange.market-data-url` in Phase 3, or `exchange.fills-stream-uri`
in Phase 4, and simply not thinking about the allowlist — because nothing forces them to. Under an
enumerated guard, **every new endpoint property is unguarded by default**. The safety of the system
then depends on a person remembering a rule at exactly the moment they are busy with something else.
That is the same category of control as a comment saying "don't forget to update the allowlist", and
it fails the same way.

This matters more here than it would elsewhere, because SPEC §1.4 deliberately provides no override
switch. A guard with no escape hatch is only as good as its coverage: a gap in coverage *is* the
escape hatch, and it is one nobody would notice, because a missing check produces no error.

## Decision

The guard covers the **`exchange.*` namespace**, not a list of names.

At startup, `ExchangeEndpointGuard` walks every enumerable property source and verifies each
property in the namespace that carries an endpoint, where "carries an endpoint" means either:

- the property **name** reads like one — after stripping punctuation it ends in `url`, `uri`, or
  `endpoint`; or
- the property **value** begins with a URL scheme (`^[a-zA-Z][a-zA-Z0-9+.-]*://`).

Either signal is sufficient. Requiring both would let `exchange.fallback=https://…` through for want
of a suffix, and a value-only rule would miss a name-like property holding a bare host.

Two properties remain named explicitly — `exchange.rest-base-url` and `exchange.ws-base-url` — for
the opposite reason: they must **exist**. Namespace scanning verifies whatever is present, so
deleting a required endpoint from configuration would otherwise silently remove its own check.

Coverage is enforced by test, not by convention. `GuardCoversEveryEndpointPropertyTest` reflects over
`ExchangeProperties`, derives every endpoint-shaped accessor, and generates a case per property
asserting that a mainnet value aborts startup. **Adding an endpoint property adds its own test
case**, including for whoever forgot. Two further cases simulate the Phase 3/4 scenario directly:
properties that exist in configuration but on no `@ConfigurationProperties` class are still refused.

The namespace also carries a meaning that the code now enforces: *any URL under `exchange.*` is an
endpoint this service may talk to.* It is not a place for a documentation link or a support address.

## Consequences

**What this buys**

- New endpoint properties are covered on the day they are introduced, with no ceremony and no
  memory required. The Phase 3 WebSocket work inherits the guarantee rather than re-earning it.
- The failure mode moves from "silently unguarded" to "loudly refuses to start" — the direction that
  matters when the check has no override.
- The reflection-generated test means coverage cannot quietly regress: deleting the guard's
  namespace scan fails several tests at once, not zero.

**What this costs, honestly**

- **False positives are possible by construction.** A legitimate non-venue URL under `exchange.*` —
  say `exchange.docs-url` — would abort startup. This is intended (the namespace means endpoints),
  but it is a real constraint on future naming, and a developer who hits it without reading this ADR
  will experience it as the guard being wrong. That is the moment where someone reaches for an
  override, which is exactly what must not exist; the mitigation is that the failure message names
  the property and the rule.
- **Detection is heuristic, not typed.** A property holding a bare host with no scheme
  (`exchange.gateway=api.example.com`) matches neither signal unless its name reads like an endpoint.
  The stronger design is a dedicated `Endpoint` value type that cannot be constructed without passing
  the allowlist, making coverage a compile-time property rather than a startup scan. That is the
  right destination; it is deferred because there is no adapter code to type yet, and introducing the
  type before its first use would be speculative. **This should be revisited when Phase 3 introduces
  the exchange client** — if it is not, this paragraph is the record of the debt.
- **Non-enumerable property sources cannot be scanned.** They are rare in practice, and the required
  properties are still resolved through them, but coverage is not total in principle.

**Related enforcement (Phase 1)**

ADR-0002 owes an ArchUnit boundary test. It will carry a rule extending this decision: outside the
`exchange` module, no code may construct an HTTP or WebSocket client. Configuration-level coverage
stops something from being *configured* to reach the wrong venue; the ArchUnit rule stops something
from being *written* to bypass configuration entirely.

## Alternatives considered

- **Keep the enumerated list, add a checklist to the PR template.** Zero implementation cost, and it
  relies on human attention at the exact moment attention is scarce. Rejected: a control that fails
  silently is not a control.
- **Validate with `@ConfigurationProperties` on a typed `URI` field per endpoint.** Type-safe and
  idiomatic, but it covers only declared properties — it cannot see a property that exists in
  configuration and nowhere else — and it fails after context refresh, which ADR-0001 already
  rejected for this check.
- **Guard every property in the whole application, not just `exchange.*`.** Maximal coverage, and it
  would reject Spring's own URLs (datasource JDBC URLs, actuator paths) or require an exclusion list —
  which reintroduces the enumeration problem, one namespace further out.
