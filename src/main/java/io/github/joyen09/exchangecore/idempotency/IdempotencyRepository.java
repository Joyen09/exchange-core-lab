package io.github.joyen09.exchangecore.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for HTTP request de-duplication. */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, row) -> new IdempotencyRecord(
            rs.getString("key"),
            rs.getString("request_hash"),
            rs.getObject("http_status", Integer.class),
            rs.getString("response_body"),
            rs.getObject("resource_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant());

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the duplicate key, for the usual reason: a
     * failed statement would abort the transaction this method is called in.
     *
     * @return {@code true} if this caller now owns the key
     */
    public boolean claim(String key, String requestHash, Instant now, Instant expiresAt) {
        List<String> claimed = jdbc.query(
                """
                INSERT INTO idempotency_keys (key, request_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (key) DO NOTHING
                RETURNING key
                """,
                (rs, row) -> rs.getString("key"),
                key,
                requestHash,
                Timestamp.from(now),
                Timestamp.from(expiresAt));
        return !claimed.isEmpty();
    }

    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.query(
                        """
                        SELECT key, request_hash, http_status, response_body, resource_id, created_at,
                               completed_at, expires_at
                          FROM idempotency_keys
                         WHERE key = ?
                        """,
                        MAPPER,
                        key)
                .stream()
                .findFirst();
    }

    public void complete(String key, int httpStatus, String responseBody, UUID resourceId, Instant completedAt) {
        jdbc.update(
                """
                UPDATE idempotency_keys
                   SET http_status = ?, response_body = ?, resource_id = ?, completed_at = ?
                 WHERE key = ?
                """,
                httpStatus,
                responseBody,
                resourceId,
                Timestamp.from(completedAt),
                key);
    }

    /** Releases a claim whose work failed, so the caller can retry instead of meeting 409 for a day. */
    public void release(String key) {
        jdbc.update("DELETE FROM idempotency_keys WHERE key = ? AND http_status IS NULL", key);
    }

    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM idempotency_keys WHERE expires_at < ?", Timestamp.from(now));
    }

    /**
     * Clears claims that were never completed — the window where the process died between committing
     * the claim and committing the work. Without this they would answer 409 until they expired.
     */
    public int deleteAbandoned(Instant olderThan) {
        return jdbc.update(
                "DELETE FROM idempotency_keys WHERE http_status IS NULL AND created_at < ?", Timestamp.from(olderThan));
    }
}
