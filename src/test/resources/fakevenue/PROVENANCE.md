# Fixture provenance

These payloads are the single definition of the wire format in this repository. The fake venue answers
with them and the adapter deserialises them, so there is one statement of what Binance sends rather than
two implementations that have to agree (ADR-0009, "Shape is closable").

| Fixture | Source | Retrieved |
|---|---|---|
| `order.json` | `/products/spot/testnet/web-socket-api` — *Query order (USER_DATA)*, the `result` object at line 115300 of `llms-full.txt` | 2026-10-13 |
| `myTrades.json` | `/products/spot/testnet/web-socket-api` — *Account trade list*, the `result` array at line 115900 | 2026-10-13 |
| `executionReport.json` | `/products/spot/testnet/user-data-stream` — the payload example at line 116663 | 2026-10-13 |

`docs/vendor/README.md` has the command to re-download the source file; it is git-ignored.

## These are documentation examples, not captures — and that is a weaker provenance

ADR-0009 prefers fixtures captured from a real testnet session by `make testnet-check`, and falls back to
the documentation's examples when no credentials are available. This project's development environment
cannot reach `testnet.binance.vision`, so **every fixture here is the fallback**.

The difference matters and is not cosmetic: a documented example is what Binance says it sends, which is
the same class of evidence as the rest of `binance-citations.md`. A capture is what it actually sent. The
first `make testnet-check` run with real credentials should replace these and commit the diff — and if
that diff is not empty, the gap between the two is the thing to read carefully.

## Every edit made to the source text, itemised

The documented examples are **not valid JSON**, so converting them was not mechanical. Each change is
listed so that the next person comparing a fixture against the document knows where a difference came
from, rather than thinking they have found something.

### `order.json` — from lines 115331–115358

| Edit | Source line | Why |
|---|---|---|
| unwrapped from `"result": { … }` | 115331, 115358 | the WebSocket API nests the order inside a response envelope; `GET /api/v3/order` returns the object bare |
| removed `//` comments | 115334, 115344–115349, 115352–115354, 115356–115357 | not JSON |
| **inserted a missing comma** after `"origQuoteOrderQty": "0.00000000"` | 115352 | a typo in the published document: line 115353 begins a new field with no separator |
| omitted `trailingDelta`, `trailingTime` | 115345–115346 | marked *"present only if trailingDelta set for the order"* |
| omitted `strategyId`, `strategyType` | 115353–115354 | marked *"present only if … set for the order"* |
| omitted `preventedMatchId`, `preventedQuantity` | 115356–115357 | marked *"present only if the order expired due to STP"* |

Kept, because the document calls them always present: `stopPrice`, `icebergQty`, `origQuoteOrderQty`.
Field order is the document's, with the omitted lines closed up.

### `myTrades.json` — from lines 115900–115935

| Edit | Why |
|---|---|
| unwrapped from `"result": [ … ]` | same envelope difference as above |
| kept both array elements | one trade would not show that a single order produces several, which is what the partial-fill tests are about |

No comments to strip and no fields omitted: the trade object as documented is complete and
unconditional. Note what is *not* there — no `clientOrderId`, no `origClientOrderId`. That absence is
load-bearing and `VenueFixturesTest` asserts it.

### `executionReport.json` — from line 116663

| Edit | Why |
|---|---|
| removed `//` comments | not JSON |
| **unwrapped from `{"subscriptionId": 0, "event": { … }}`** | see the warning below — this is the least certain edit here |

Nothing was omitted; the conditional fields listed under *"Conditional Fields in Execution Report"* were
simply not in the example.

## An open protocol question, recorded rather than resolved

**The envelope of `executionReport` depends on which transport delivers it, and the two are documented
unevenly.**

- Both user-data-stream pages — production and testnet — show the payload wrapped in
  `{"subscriptionId": 0, "event": { … }}`. That is the **WebSocket API subscription** transport, where
  a client subscribes over an authenticated connection.
- Phase 3 chose the **classic** transport: `POST /api/v3/userDataStream` for a listenKey, renewed every
  thirty minutes, consumed at `/ws/{listenKey}`. That one delivers the event **bare**.

In the vendored extract, the only bare examples are in `/products/spot/faqs/market_orders_faq` (lines
130998, 131034, 131070, 131106). So the fixture here is unwrapped — which matches the transport the
project is building against — but it was produced by *removing* an envelope from an example of a
different transport, rather than copied from an example of the right one.

The FAQ examples corroborate the shape. They are an FAQ, which is weaker evidence than a reference
page, and that is the honest status: **the bare envelope is corroborated, not attested.** The first
`make testnet-check` against a real listenKey settles it in one line of output, and until then this
paragraph is the citation.

## Rules

- **Do not hand-edit a fixture to make a test pass.** A fixture is evidence about the venue, not a knob.
  If the adapter and the fixture disagree, the adapter is wrong until the document says otherwise.
- **A change to a fixture cites its source line**, in the commit message, exactly as changes to the fake
  venue must (ADR-0009).
- **A re-capture lands in its own commit**, so that "Binance changed" and "we changed" are never the same
  diff.
