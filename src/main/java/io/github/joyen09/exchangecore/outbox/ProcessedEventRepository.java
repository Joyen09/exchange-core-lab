package io.github.joyen09.exchangecore.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Consumer-side de-duplication state. */
@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an event as processed.
     *
     * @return {@code true} if this is the first time it has been seen; {@code false} means the broker
     *     delivered it again, which is expected and not an error
     */
    public boolean recordIfNew(UUID eventId, UUID aggregateId, Long sequenceNo, Instant now) {
        List<UUID> inserted = jdbc.query(
                """
                INSERT INTO processed_events (event_id, aggregate_id, sequence_no, processed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                RETURNING event_id
                """,
                (rs, row) -> rs.getObject("event_id", UUID.class),
                eventId,
                aggregateId,
                sequenceNo,
                Timestamp.from(now));
        return !inserted.isEmpty();
    }

    public Optional<Long> highestSequenceFor(UUID aggregateId) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT MAX(sequence_no) FROM processed_events WHERE aggregate_id = ?", Long.class, aggregateId));
    }

    public long countFor(UUID aggregateId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE aggregate_id = ?", Long.class, aggregateId);
        return count == null ? 0L : count;
    }

    public int deleteProcessedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM processed_events WHERE processed_at < ?", Timestamp.from(cutoff));
    }
}
