# ADR-0009 — A fake venue for behaviour, the real testnet for protocol

- **Status:** Accepted
- **Date:** 2026-10-13
- **Phase:** 3

## Context

Phase 3's acceptance criteria are, in the SPEC's own words, the most important in the project. Four of
them:

1. Chaos: cut the WebSocket at random points; the final order state still matches the exchange.
2. Out of order: deliver fills out of order and duplicated; the final state is still correct.
3. Partial fills: one order filled in five parts; cumulative quantity and average price are right.
4. Retry on 5xx without creating a duplicate order.

**Three of those four cannot be produced on a real exchange.** You cannot ask Binance to drop your
WebSocket after the second fill, to redeliver an execution report, or to return 503 twice and then
succeed. You can wait and hope — which is not a test, it is a vigil.

So the question is not whether to build a test double. It is what the double is allowed to be
evidence *of*.

A second constraint shapes this: the network policy of the development environment denies
`testnet.binance.vision`, `stream.testnet.binance.vision` **and `developers.binance.com`**. The last
one is the significant denial. It means the adapter and the double were written without the official
protocol reference in reach, which is a fidelity risk of a different kind from the ones below — see
"What would have to be true for this to fool us".

## Options considered

### A. Only the real testnet

Every test drives `testnet.binance.vision` with real credentials.

**For:** nothing is simulated, so nothing can be simulated *wrongly*. The payloads, the signature
scheme, the error codes and the field names are whatever Binance actually sends, which is the one thing
a double can never guarantee.

**Against:** it cannot express three of the four acceptance criteria, which is disqualifying on its own.
Beyond that: the tests become dependent on an external service's availability and rate limits, so a red
build stops meaning "the code is wrong"; they need live credentials, which this project has decided do
not enter a cloud session (SPEC §1.3); and they are not reproducible — a chaos test that cannot be
re-run with the same seed is an anecdote.

### B. Only a fake venue

An in-process HTTP + WebSocket server, scripted and seeded.

**For:** every acceptance criterion becomes a deterministic, re-runnable input. "Disconnect after the
second fill" is a line in a script. Failures reproduce exactly. No network, no credentials, no rate
limits, and CI runs offline.

**Against:** it is written from a reading of the documentation, so **every misunderstanding of the
protocol is faithfully reproduced in the double and then confirmed by the tests**. A test suite that is
green against a double that is wrong in the same way the adapter is wrong tells you nothing at all, and
tells it very convincingly. This is not a hypothetical risk; a concrete instance of it is recorded
below.

### C. Record and replay the real venue

Capture real responses once, replay them in tests (a VCR-style cassette).

**For:** the payloads are genuine, and tests run offline afterwards.

**Against:** recordings can only replay scenarios that were recorded, and the three impossible scenarios
are impossible to record for the same reason they are impossible to test. Worse, a cassette goes stale
silently: when Binance changes a field, the recording keeps the old one and the suite stays green while
production breaks. It has option B's fidelity problem with a longer fuse and no script.

### D. Both, with the work divided by what each can prove — chosen

- **Behaviour** — ordering, recovery, idempotency, retry, rate limiting — is proven against the fake
  venue. Deterministic, seeded, offline, and run in CI.
- **Protocol** — signature construction, parameter names, response deserialisation, error-code
  translation — is proven against the real testnet, in an opt-in test that is skipped unless
  `BINANCE_TESTNET_API_KEY` is present, and is never run in CI.

**For:** each half is tested by the only thing that can test it. The division is also a statement a
reader can check: if a bug is in the first category and reached production, the fake venue was wrong; if
it is in the second, the opt-in test was not run.

**Against:** two things to maintain, and a seam between them where a bug can live in neither's
jurisdiction — a protocol detail that the fake happens to implement the same wrong way the adapter reads
it, and that the opt-in test does not happen to exercise. That seam is real and is not closed by this
decision.

## Decision

Option D. `ExchangeClient` is an interface in domain vocabulary; `BinanceRestClient` implements it for
the real venue and `FakeVenueClient` for tests.

### The interface leaks no Binance

```java
PlaceResult place(PlaceCommand cmd);          // venueOrderId, or Rejection(reason)
CancelResult cancel(CancelCommand cmd);
OrderSnapshot fetchOrder(ClientOrderId id);
List<OrderSnapshot> fetchOpenOrders(Symbol symbol);
List<TradeSnapshot> fetchTrades(Symbol symbol, Instant since);
```

No `-2010`, no `origClientOrderId`, no `executionReport` above this line. Binance's error codes are
translated into a `RejectionReason` enum inside `BinanceRestClient`. This is not tidiness: it is what
makes the fake venue a *substitute* rather than a second implementation of Binance. A domain layer that
knew about `-2010` would have to be taught about the fake's `-2010` too, and the double would stop
being a double.

The fake venue lives in `src/test/java/.../fakevenue/` and nothing in `src/main` may reference it. If
the adapter ever has to change behaviour to accommodate the double, the abstraction is in the wrong
place and the fix is the abstraction, not the adapter.

### Signature verification is implemented in the fake, deliberately

The fake venue verifies HMAC-SHA256 over the query string, including `timestamp` and `recvWindow`, and
answers `-1022` when it does not match. Faking this away would be the obvious economy and the wrong one:
request signing is the single piece of this adapter most likely to be subtly wrong, and leaving it
unverified in every offline test means it is exercised only by a test that is skipped by default.

This is the one place where the fake's fidelity risk is acceptable in both directions — if my HMAC is
wrong in the same way in both the client and the fake, the opt-in test catches it immediately, because a
wrong signature is the one error the real venue reports unambiguously.

## The fidelity risk, stated without softening

**The fake venue is written from my reading of the documentation. Where that reading is wrong, the
double is wrong in exactly the same way as the adapter, the tests pass, and the error is invisible until
it reaches the real venue.**

That sentence is the most important one in this ADR, and it is not a formality. Here is the instance
that happened while this document was being written:

> The Phase 3 specification said that a duplicate `newClientOrderId` is rejected with **`-2026`**. I
> believed it was **`-2010`**. The official error table confirms `-2010 NEW_ORDER_REJECTED`, and that
> `-2026` is `ORDER_ARCHIVED` — an order older than 90 days — which has nothing to do with duplicate
> submission. Two people reasoning carefully about the same protocol produced two different constants,
> and either of them, written into the fake venue, would have produced a green suite and a broken
> adapter.

Binance's own documentation names the trap:

> Errors consist of two parts: an error code and a message. Codes are universal, but messages can vary.

And `-2010` proves it: the same code carries `"Duplicate order sent."` (the client order id is already
in use) and `"Account has insufficient balance for requested action."` (no funds). **One code, two
outcomes that must be handled in opposite ways** — one is "the order probably exists, go and find it",
the other is "the order was refused, mark it rejected". Branching on a message string that the vendor
reserves the right to change is not a defensible design.

### So the adapter does not branch on the message

On `-2010`, `BinanceRestClient` issues `GET /api/v3/order?origClientOrderId=…`:

- **order exists** → this was a duplicate submission; return the existing state and take the recovery
  path.
- **order does not exist** → this was a genuine rejection; translate to `RejectionReason`.

The message string is written to the log as a hint and is never a branch condition. The cost is one
extra round trip on an uncommon path; what it buys is an adapter that survives Binance rewording an
error message.

**The WebSocket path does not need this.** `executionReport` carries a structured reject-reason field
`r` with enumerated values (`INSUFFICIENT_BALANCES`, `STOP_PRICE_WOULD_TRIGGER_IMMEDIATELY`, …), so
events are branched on the enum. Only the REST path is reduced to code-plus-prose, and only the REST
path pays for a lookup.

### What would have to be true for this strategy to fool us

Written as a list because "we tested it" is not an answer to "how would you know":

1. **The fake implements a field the same wrong way the adapter reads it, and the opt-in test never
   touches that field.** The most likely shape: an optional field that is absent in the simple cases the
   opt-in test exercises and present in the ones only the fake produces. Mitigation: the opt-in test
   asserts on *deserialisation of the full response object*, not on the two or three fields a happy path
   needs.
2. **The fake's state machine is more forgiving than the real venue's.** For instance, if the real venue
   refuses a cancel on an order that is already `PENDING_CANCEL` and the fake accepts it, the adapter
   never learns to handle the refusal. Mitigation: the fake refuses everything the documentation says is
   refused, and the opt-in test includes one deliberate protocol violation to confirm the real venue
   refuses it too.
3. **The real venue's timing differs qualitatively, not just quantitatively.** The fake delivers the
   execution report after the REST response returns. If the real venue can deliver it *before*, there is
   a race the fake never produces. Mitigation: the fake can be scripted to deliver the report first, and
   at least one test does so — because "the double is deterministic" must not come to mean "the double
   only ever does the convenient thing".
4. **~~The official reference is not in the repository, so the fake encodes my memory.~~** *Closed —
   cited, with retrieval dates.* `docs/vendor/binance-citations.md` holds the quotations the code
   depends on, each with its Spot document path, line number and the date the source was retrieved.
   Every protocol constant in the fake venue now has a citable source rather than a recollection.

   The full 8.2 MB document is **not** committed, and the reason is itself a decision worth recording:
   it is © Binance with all rights reserved, and this repository is MIT-licensed, so redistributing it
   wholesale would licence someone else's reserved-rights work under terms that permit copying and
   sublicensing. Short attributed quotations for the purpose of documenting an interface are a
   different thing. `docs/vendor/README.md` has the re-download command; the file is git-ignored.

Item 4 was the one that worried me most, because it was the only one whose mitigation was not code I
could write. It is closed, and closing it immediately paid for itself — see below.

### What the vendored reference confirmed, and what it corrected

Checked against the file as soon as it landed, before any of the adapter was written:

| Claim | Verdict | Cited at |
|---|---|---|
| `-2010 NEW_ORDER_REJECTED` carries `"Duplicate order sent."` meaning *the `clOrdId` is already in use* | confirmed verbatim | citations §2 |
| the same code also carries `"Account has insufficient balance for requested action."` | confirmed — one code, two opposite handlings | citations §2 |
| `-2026` is `ORDER_ARCHIVED`, unrelated to duplicate submission | confirmed: *"canceled or expired with no executed qty over 90 days ago"* | citations §3 |
| "Codes are universal, but messages can vary" | confirmed verbatim | citations §1 |
| `z` cumulative filled quantity, `Z` cumulative quote quantity | confirmed | citations §4 |
| average price is `Z / z` | confirmed verbatim: *"Average price can be found by doing `Z` divided by `z`."* | citations §5 |
| `executionReport` carries a structured reject reason `r`, including `INSUFFICIENT_BALANCES` | confirmed | citations §4, §6 |

Two things the check produced that no amount of care would have:

- **`myTrades` is a per-symbol endpoint.** It refuses a request without `symbol`, so recovery costs one
  call per symbol rather than one call. ADR-0011's cost estimate was wrong until this was read, and the
  corrected figure makes its own argument stronger (citations §7, §8).
- **The citations had to be taken from the right product.** The source document concatenates every
  Binance product and the error pages repeat almost identically for each; the first copy of the `-2010`
  table in the file is under `/products/wallet/error-code`. A citation from there would have agreed with
  the Spot page by coincidence rather than by authority, which is a worse kind of wrong than no citation
  at all.

So the specification's `-2026` was wrong and my `-2010` was right — but that is not the lesson, because
the two of us disagreeing was the only reason anybody looked. **The lesson is that neither of us had a
citation until the file existed.**

And reading it immediately produced a detail neither of us had:

> `"t": -1,  // Trade ID`

`t` is **−1 when the event is not a trade** — a `NEW` or `CANCELED` report carries no trade id, and `n`
(commission) and `N` (commission asset) are `"0"` and `null` alongside it. Deduplicating on
`(symbol, tradeId)` without first checking `x == "TRADE"` would therefore insert `(ETHBTC, -1)` as a
real key, and the *second* non-trade event for that symbol would be silently discarded as a duplicate.
That defect would have passed every test in the plan, because a fake venue written from the same memory
would have emitted the same `-1`.

One file, read for ten minutes, turned a confirmed-but-uncited belief into a cited one and caught a bug
that the double was going to hide. That is the whole argument for the vendoring rule in
`docs/vendor/README.md`.

## What we gave up

- **CI never touches a real exchange.** A green build means the behaviour is right and says nothing
  about whether the wire format is. The project accepts that and makes it visible rather than implied:
  the opt-in test has its own command (`make testnet-check`), and its result is reported by the author
  rather than by the build.
- **A whole exchange had to be written to test an adapter.** The fake venue is a substantial piece of
  test code that ships no value to a user and must be maintained as Binance changes. That is the price
  of the three criteria that cannot be tested otherwise, and it is not small.
- **The seam between the two halves belongs to nobody.** A defect that is behavioural in the fake's eyes
  and protocol-level in the opt-in test's eyes falls between them. We have not closed this; we have
  listed the four shapes it could take, which is the best available substitute for closing it.
- **The double can be made to agree with us.** When a test fails, there are two ways to make it pass,
  and one of them is to change the fake. That temptation is strongest exactly when the adapter is
  wrong. The rule adopted here: **a change to the fake venue that makes a failing test pass must cite
  the documentation line that justifies it, in the commit message.** No citation, no change.
- **Rate-limit behaviour is tested against a simulation of a policy, not the policy.** The fake tracks
  weight and answers 429 and 418 as documented. Whether Binance's real accounting agrees with ours —
  which endpoints cost what — is unverified until someone runs the opt-in test hard enough to find out,
  and nobody will.

## Consequences

- **`ExchangeClient` is the boundary that must stay clean**, and an ArchUnit rule enforces it: nothing
  outside the `exchange` package may reference Binance types, and nothing in `src/main` may reference
  the fake venue.
- **The fake is script-driven and seeded.** The same seed must produce the same event sequence, and a
  meta-test asserts it by running one script twice and comparing the sequences element by element. A
  chaos test that is not reproducible is worse than no chaos test, because it fails once and is then
  retried until it passes.
- **Fills that happen while the WebSocket is down are still recorded in the fake's internal state.**
  This is what makes acceptance criterion 1 meaningful: the client can only learn about them by
  reconnecting and asking, which is precisely the behaviour under test (ADR-0011).
- **The opt-in test asserts protocol, never behaviour.** If it ever starts asserting that an order ends
  in the right state, it has become a flaky duplicate of the fake-venue suite and should be cut back.
- **The README states the division in the Known limitations section**, in the same terms as this
  document: behaviour is verified against a double of my own construction; protocol is verified only
  when a human runs one command with real credentials.
