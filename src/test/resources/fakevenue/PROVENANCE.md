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

## What had to be changed to make them parse, and why that is a risk

The documented examples are **not valid JSON**. Converting them was not mechanical, and each change is a
place where a reading of the document entered the fixture:

1. **`//` comments stripped.** The examples annotate fields inline. Removing them is safe.
2. **A missing comma repaired.** In the *Query order* example the line
   `"origQuoteOrderQty": "0.00000000"` is followed by `"strategyId"` with no separating comma — a typo in
   the published document.
3. **Conditional fields omitted.** The example marks `trailingDelta`, `trailingTime`, `strategyId`,
   `strategyType`, `preventedMatchId` and `preventedQuantity` as "present only if…". They are left out,
   because including them would assert they are always sent, which the document says they are not.

Item 3 is the one that could bite. It is a judgement about when a field appears, made from prose, and a
wrong judgement here produces a fixture the adapter parses happily and a real response it does not. That
is the **meaning** half of ADR-0009's item 1, in miniature, and no mechanism in this repository closes
it — only the capture in the first paragraph would.

## Rules

- **Do not hand-edit a fixture to make a test pass.** A fixture is evidence about the venue, not a knob.
  If the adapter and the fixture disagree, the adapter is wrong until the document says otherwise.
- **A change to a fixture cites its source line**, in the commit message, exactly as changes to the fake
  venue must (ADR-0009).
- **A re-capture lands in its own commit**, so that "Binance changed" and "we changed" are never the same
  diff.
