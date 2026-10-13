# Vendored vendor documentation

Third-party reference material committed verbatim, so that code written against it can be checked
against what it was actually written against.

## Why this directory exists

The fake Binance venue in `src/test/.../fakevenue/` is built from a reading of Binance's Spot API
documentation. Where that reading is wrong, the double is wrong in the same way as the adapter, the
tests pass, and nothing notices until the real venue disagrees — ADR-0009 states that risk plainly and
this directory is its main mitigation.

It is a mitigation rather than a fix. A vendored document turns "I believe the error code is X" into a
claim with a citation that a reviewer can check, and it pins the version the code was written against so
a later change at the vendor shows up as a diff rather than as a mystery.

## Expected contents

| File | Source | Fetched by |
|---|---|---|
| `binance-spot-api.txt` | `https://developers.binance.com/en/docs/llms-full.txt` | the author, locally |

The development environment's network policy denies `developers.binance.com`, so this file cannot be
fetched from a cloud session. It is downloaded on the author's machine and committed.

## Required header

Prepend these lines to the downloaded file, so the provenance travels with it:

```
# Source:     https://developers.binance.com/en/docs/llms-full.txt
# Downloaded: YYYY-MM-DD
# Vendored verbatim. Do not edit. Re-download to update, and commit the diff separately.
```

## Rules

- **Verbatim.** Never edited to match what the code expects. If the document and the code disagree, the
  code is wrong until proven otherwise.
- **Cited.** A change to the fake venue that makes a failing test pass must quote the line of this file
  that justifies it, in the commit message (ADR-0009).
- **Separate commits.** A re-download lands on its own, so that "the vendor changed" and "we changed"
  are never the same diff.
