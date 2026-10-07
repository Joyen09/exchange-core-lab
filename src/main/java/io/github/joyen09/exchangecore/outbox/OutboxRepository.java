package io.github.joyen09.exchangecore.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Outbox persistence.
 *
 * <p>{@link #append} is called from inside the business transaction and is the only write the business
 * code performs towards the broker — nothing here or above it touches Kafka. That separation is the
 * entire point of the pattern: if the state change commits, the message exists; if it rolls back,
 * neither happened.
 */
@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxRecord> MAPPER = (rs, row) -> new OutboxRecord(
            rs.getLong("id"),
            rs.getString("aggregate_type"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
            rs.getInt("attempts"),
            rs.getTimestamp("next_attempt_at").toInstant(),
            rs.getString("last_error"),
            rs.getTimestamp("dead_at") == null ? null : rs.getTimestamp("dead_at").toInstant());

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the transaction that makes the state change it describes. */
    public void append(
            String aggregateType,
            UUID aggregateId,
            UUID eventId,
            String eventType,
            String payloadJson,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO outbox (aggregate_type, aggregate_id, event_id, event_type, payload,
                                    created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                aggregateType,
                aggregateId,
                eventId,
                eventType,
                payloadJson,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    /**
     * Claims a batch for this publisher only.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes more than one publisher safe: a second instance
     * passes over the locked rows instead of blocking on them or duplicating them. This project assumes
     * a single instance, and writing it this way means that assumption failing is not an incident.
     */
    public List<OutboxRecord> claimBatch(int limit, Instant now) {
        return jdbc.query(
                """
                SELECT id, aggregate_type, aggregate_id, event_id, event_type, payload, created_at,
                       published_at, attempts, next_attempt_at, last_error, dead_at
                  FROM outbox
                 WHERE published_at IS NULL
                   AND dead_at IS NULL
                   AND next_attempt_at <= ?
                 ORDER BY id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """,
                MAPPER,
                Timestamp.from(now),
                limit);
    }

    public void markPublished(long id, Instant publishedAt) {
        jdbc.update("UPDATE outbox SET published_at = ?, last_error = NULL WHERE id = ?", Timestamp.from(publishedAt), id);
    }

    public void markFailed(long id, int attempts, Instant nextAttemptAt, String error) {
        jdbc.update(
                "UPDATE outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                attempts,
                Timestamp.from(nextAttemptAt),
                error,
                id);
    }

    /** Terminal: stop retrying, keep the row, and let the metric say so. Never silently dropped. */
    public void markDead(long id, int attempts, Instant deadAt, String error) {
        jdbc.update(
                "UPDATE outbox SET attempts = ?, dead_at = ?, last_error = ? WHERE id = ?",
                attempts,
                Timestamp.from(deadAt),
                error,
                id);
    }

    public long backlog() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL AND dead_at IS NULL", Long.class);
        return count == null ? 0L : count;
    }

    public long deadCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE dead_at IS NOT NULL", Long.class);
        return count == null ? 0L : count;
    }

    public List<OutboxRecord> findByAggregate(UUID aggregateId) {
        return jdbc.query(
                """
                SELECT id, aggregate_type, aggregate_id, event_id, event_type, payload, created_at,
                       published_at, attempts, next_attempt_at, last_error, dead_at
                  FROM outbox
                 WHERE aggregate_id = ?
                 ORDER BY id
                """,
                MAPPER,
                aggregateId);
    }

    /** Retention: published rows are kept for forensics, then removed on a schedule. */
    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM outbox WHERE published_at IS NOT NULL AND published_at < ?",
                Timestamp.from(cutoff));
    }
}
