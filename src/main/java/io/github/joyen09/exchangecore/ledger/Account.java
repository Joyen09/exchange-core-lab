package io.github.joyen09.exchangecore.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * An account identity. Deliberately carries no balance field — the balance is derived from postings
 * and exists in exactly one place (ADR-0005). A balance on this record would be a snapshot by
 * another name, and would be stale the moment it was constructed.
 */
public record Account(UUID id, String ownerId, String asset, AccountType type, Instant createdAt) {}
