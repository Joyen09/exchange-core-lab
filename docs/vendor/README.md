# Vendor documentation: cited, not copied

`binance-citations.md` holds short quotations from Binance's Spot API documentation, each with the
document path, the line number and the retrieval date, so that every protocol constant in this
repository has a source a reviewer can check.

## Why the full document is not here

An earlier version of this directory committed Binance's complete `llms-full.txt` — 8.2 MB, 211,182
lines. That was removed before it reached `main`, for a reason that matters more than repository size:

**The document is © Binance, all rights reserved. This repository is MIT-licensed.** Committing the
whole thing would redistribute someone else's reserved-rights work under a licence that grants everyone
the right to copy, modify and sublicense it. A repository that argues at length about doing things
properly should not get that wrong on page one.

Short quotations for the purpose of documenting and verifying an interface, attributed and dated, are a
different thing from wholesale redistribution. That is what `binance-citations.md` is.

The size was the lesser problem, but it was real too: an 8.2 MB blob is permanent in git history, and
every clone pays for it forever.

## Getting the full document for local use

```bash
curl -o docs/vendor/binance-spot-api.txt https://developers.binance.com/en/docs/llms-full.txt
```

`binance-spot-api.txt` is git-ignored. Keep it locally while working on the exchange adapter; it is the
reference the citations are taken from.

If `curl` fails, the host may be blocked by your network policy — this project's cloud development
environment denies it, which is why the citations exist as a committed artefact rather than being
re-derived on demand.

Line numbers in `binance-citations.md` refer to the file exactly as downloaded, with no local edits. Do
not add a header to it; a prepended header shifts every line number and silently invalidates every
citation.

## Rules

- **Quote, never paraphrase.** An entry that summarises what the document means is an opinion with a
  line number attached.
- **Spot sections only.** The source file concatenates every Binance product, and the error pages repeat
  almost identically for each. A citation taken from `/products/wallet/error-code` when the code runs
  against Spot is a citation of the wrong document that happens to agree.
- **A change to the fake venue that makes a failing test pass must cite an entry here**, in the commit
  message (ADR-0009). No citation, no change — otherwise the double gets adjusted to agree with the
  adapter, which is the exact failure the double exists to prevent.
- **A protocol constant with no entry here is a guess**, and the comment next to it should say so.
