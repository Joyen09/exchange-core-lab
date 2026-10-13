# Binance Spot API — cited excerpts

Short quotations from Binance's published API documentation, kept so that every protocol constant in
this repository has a checkable source rather than a recollection (ADR-0009).

- **Source:** `https://developers.binance.com/en/docs/llms-full.txt`
- **Retrieved:** 2026-10-13
- **Line numbers** refer to that file exactly as downloaded, 211,182 lines, no local modification.

> The full document is **not** committed. It is © Binance, all rights reserved, and this repository is
> MIT-licensed; redistributing 211,182 lines of someone else's reserved-rights documentation under MIT
> would be a licensing error. These excerpts are short quotations for the purpose of documenting and
> verifying an interface, with attribution and a retrieval date.
>
> To re-check any line below, download the file as `docs/vendor/binance-spot-api.txt` — it is
> git-ignored — and look at the cited line. `docs/vendor/README.md` has the steps.

A note on the source file's shape: it concatenates every Binance product's documentation, and the error
pages repeat nearly verbatim for each product. Every citation below is taken from a **Spot** section
(`/products/spot/...`), not from whichever copy happened to appear first.

---

## 1. Error codes carry meaning only together with a message

**`/products/spot/errors`, line 24310**

> Errors consist of two parts: an error code and a message. Codes are universal, but messages can
> vary.

This single sentence is the reason `BinanceRestClient` never branches on an error message. See §2.

## 2. `-2010` is one code with several unrelated meanings

**`/products/spot/errors`, line 24734**

> ### -2010 NEW_ORDER_REJECTED

The messages that disambiguate it, from the table beginning at line 24792:

| Line | Message | Meaning (quoted) |
|---|---|---|
| 24792 | `"Duplicate order sent."` | "The `clOrdId` is already in use." |
| 24794 | `"Account has insufficient balance for requested action."` | "Not enough funds to complete the action." |

**One code, two outcomes requiring opposite handling** — one means the order probably exists and should
be fetched, the other means it was refused and should be marked rejected. Combined with §1, this is why
the adapter resolves `-2010` by issuing `GET /api/v3/order?origClientOrderId=…` and treats the message
as a log hint only.

The Spot page says `clOrdId`; other product copies of the same table say `clientOrderId`. Same field,
FIX naming.

## 3. `-2026` is unrelated to duplicate submission

**`/products/spot/errors`, line 24758**

> ### -2026 ORDER_ARCHIVED
>
> Order was canceled or expired with no executed qty over 90 days ago and has been archived.

Recorded because the Phase 3 specification initially named `-2026` as the duplicate-order code. It is
not. See the ADR-0009 section on fidelity.

## 4. `executionReport` field meanings

**`/products/spot/testnet/user-data-stream`** — cited from the testnet page because that is the venue
this project connects to; `/products/spot/user-data-stream` carries the same legend.

| Line | Field | Documentation comment |
|---|---|---|
| 116676 | `x` | Current execution type |
| 116678 | `r` | Order reject reason |
| 116680 | `l` | Last executed quantity |
| 116681 | `z` | Cumulative filled quantity |
| 116683 | `n` | Commission amount |
| 116684 | `N` | Commission asset |
| 116686 | `t` | Trade ID |
| 116693 | `Z` | Cumulative quote asset transacted quantity |

### 4a. The trade id is `-1` when the event is not a fill

The example payload at line 116686 is a `NEW` event, and it reads:

> ```
> "t": -1,                        // Trade ID
> ```

with `"n": "0"` and `"N": null` beside it (lines 116683–116684).

**This is the defect the vendored document caught before anything was built.** Deduplicating on
`(symbol, tradeId)` without first checking `x == "TRADE"` would write `(symbol, -1)` as a real key, and
every later non-trade event for that symbol would be discarded as a duplicate. ADR-0011 now applies the
deduplication only to trade events.

## 5. Average fill price is `Z / z`, stated by the vendor

**`/products/spot/testnet/user-data-stream`, line 116702** (and `/products/spot/user-data-stream`,
line 21165)

> **Note:** Average price can be found by doing `Z` divided by `z`.

So `avg_fill_price = Z / z` is the documented formula, not an inference.

## 6. The reject reason is an enumeration, not prose

**`/products/spot/user-data-stream`, line 21209**, from the Order Reject Reason table:

> | `INSUFFICIENT_BALANCES`                | "Account has insufficient balance for requested action." |

The WebSocket path therefore branches on the `r` enum. Only the REST path is reduced to code-plus-prose
and needs the lookup in §2.

## 7. `GET /api/v3/myTrades` requires `symbol`

**`/products/spot/CHANGELOG`, line 27823**, quoting the endpoint's own error response:

> ```
> "msg": "Mandatory parameter 'symbol' was not sent, was empty/null, or malformed."
> ```

Recovery after a reconnect therefore needs **one `myTrades` call per symbol**, not one call overall.
ADR-0011's cost estimate is written from this.

## 8. Request weights for the recovery path

**`/products/spot/CHANGELOG`, lines 27068–27077.** This is a changelog table announcing a weight
increase, with both the old and the new value; the right-hand column is the current one.

| Endpoint | Previous weight | Current weight |
|---|---|---|
| `GET /api/v3/order` | 2 | **4** |
| `GET /api/v3/openOrders` with `symbol` | 3 | **6** |
| `GET /api/v3/openOrders` without `symbol` | 40 | **80** |
| `GET /api/v3/myTrades` | 10 | **20** |

**Caveat on this citation:** these come from a changelog entry rather than from the endpoint reference
pages, which this extract of the documentation does not include in full. They are the best available
citation, not an authoritative endpoint page, and the opt-in testnet check should confirm them against
the live `X-MBX-USED-WEIGHT` header rather than trusting this table.

The unqualified `openOrders` costing **80** against a qualified one costing **6** is why the recovery
path queries per symbol wherever it can.

## 9. Rate-limit headers and ban escalation

**`/products/wallet/general-info`, lines 5060–5074** — this text is not repeated under a Spot path in
the extract, so it is cited where it appears.

> Every request will contain `X-MBX-USED-WEIGHT-(intervalNum)(intervalLetter)` in the response
> headers…

> A `Retry-After` header is sent with a 418 or 429 responses and will give the **number of seconds**…

Hence: throttle on the used-weight header, honour `Retry-After` on 429, and stop entirely on 418.

---

## How to add to this file

When the adapter or the fake venue needs a protocol fact:

1. Download the source file locally (see `README.md` in this directory).
2. Find the statement in a **Spot** section.
3. Add a numbered entry here with the quotation, the document path, and the line number.
4. Cite that entry number in the code comment or commit message that depends on it.

A protocol constant with no entry here is a guess, and should be written as one in the comment next to
it.
