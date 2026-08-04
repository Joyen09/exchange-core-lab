package io.github.joyen09.exchangecore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One side of a movement. Positive credits the account, negative debits it. */
public record Posting(UUID id, UUID entryId, UUID accountId, BigDecimal amount, Instant createdAt) {}
