package io.github.joyen09.exchangecore.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A committed, balanced movement together with the postings that make it balance. */
public record LedgerEntry(
        UUID id, String idempotencyKey, String kind, String refId, Instant createdAt, List<Posting> postings) {

    public LedgerEntry {
        postings = List.copyOf(postings);
    }
}
