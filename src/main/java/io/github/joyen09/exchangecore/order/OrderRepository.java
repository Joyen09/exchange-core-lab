package io.github.joyen09.exchangecore.order;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The only place order rows are written.
 *
 * <p>Two things here carry the weight of the design and are easy to undo by accident:
 *
 * <ul>
 *   <li>{@link #lockForTransition(UUID)} is a {@code SELECT ... FOR UPDATE}. Every transition starts
 *       with it, so two threads advancing the same order serialise rather than interleave.
 *   <li>{@code order_events} is insert-only here because it is insert-only in the database. There is
 *       deliberately no update or delete method to call.
 * </ul>
 */
@Repository
public class OrderRepository {

    private static final String ORDER_COLUMNS =
            """
            id, owner_id, client_order_id, symbol, side, type, time_in_force, quantity, price,
            filled_quantity, avg_fill_price, status, version, created_at, updated_at
            """;

    private static final RowMapper<Order> ORDER_MAPPER = (rs, row) -> new Order(
            rs.getObject("id", UUID.class),
            rs.getString("owner_id"),
            rs.getString("client_order_id"),
            rs.getString("symbol"),
            OrderSide.valueOf(rs.getString("side")),
            OrderType.valueOf(rs.getString("type")),
            rs.getString("time_in_force") == null ? null : TimeInForce.valueOf(rs.getString("time_in_force")),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("filled_quantity"),
            rs.getBigDecimal("avg_fill_price"),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<OrderEvent> EVENT_MAPPER = (rs, row) -> new OrderEvent(
            rs.getLong("id"),
            rs.getObject("order_id", UUID.class),
            rs.getLong("sequence_no"),
            OrderEventType.valueOf(rs.getString("event_type")),
            rs.getString("from_status") == null ? null : OrderStatus.valueOf(rs.getString("from_status")),
            OrderStatus.valueOf(rs.getString("to_status")),
            rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TradingSymbol> findSymbol(String symbol) {
        return jdbc.query(
                        "SELECT symbol, base_asset, quote_asset FROM symbols WHERE symbol = ?",
                        (rs, row) -> new TradingSymbol(
                                rs.getString("symbol"), rs.getString("base_asset"), rs.getString("quote_asset")),
                        symbol)
                .stream()
                .findFirst();
    }

    /**
     * Inserts the order unless its {@code client_order_id} is already taken.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the duplicate key: in PostgreSQL a failed
     * statement aborts the whole transaction, so there would be nothing left to recover in — the same
     * trap the ledger's account creation documents.
     *
     * @return {@code true} if this call created the order, {@code false} if it already existed
     */
    public boolean insertIfAbsent(Order order) {
        List<UUID> inserted = jdbc.query(
                """
                INSERT INTO orders (id, owner_id, client_order_id, symbol, side, type, time_in_force,
                                    quantity, price, filled_quantity, avg_fill_price, status, version,
                                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, client_order_id) DO NOTHING
                RETURNING id
                """,
                (rs, row) -> rs.getObject("id", UUID.class),
                order.id(),
                order.ownerId(),
                order.clientOrderId(),
                order.symbol(),
                order.side().name(),
                order.type().name(),
                order.timeInForce() == null ? null : order.timeInForce().name(),
                order.quantity(),
                order.price(),
                order.filledQuantity(),
                order.avgFillPrice(),
                order.status().name(),
                order.version(),
                Timestamp.from(order.createdAt()),
                Timestamp.from(order.updatedAt()));
        return !inserted.isEmpty();
    }

    /**
     * Locks the order row for the duration of the transaction. Every transition begins here; without
     * it, two concurrent transitions would read the same current status and both think themselves
     * legal.
     */
    public Optional<Order> lockForTransition(UUID orderId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = ? FOR UPDATE", ORDER_MAPPER, orderId)
                .stream()
                .findFirst();
    }

    public Optional<Order> findById(UUID orderId) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = ?", ORDER_MAPPER, orderId).stream()
                .findFirst();
    }

    /**
     * Looks the order up by the name its owner gave it. Both halves of the key are required, because the
     * unique constraint is {@code (owner_id, client_order_id)} — a lookup on the id alone would match
     * another owner's order, which is exactly the confusion the scoped constraint exists to prevent.
     */
    public Optional<Order> findByClientOrderId(String ownerId, String clientOrderId) {
        return jdbc.query(
                        "SELECT " + ORDER_COLUMNS + " FROM orders WHERE owner_id = ? AND client_order_id = ?",
                        ORDER_MAPPER,
                        ownerId,
                        clientOrderId)
                .stream()
                .findFirst();
    }

    /** Writes the projection. {@code version} is incremented here and nowhere else. */
    public void updateProjection(
            UUID orderId,
            OrderStatus status,
            BigDecimal filledQuantity,
            BigDecimal avgFillPrice,
            Instant updatedAt) {
        jdbc.update(
                """
                UPDATE orders
                   SET status = ?, filled_quantity = ?, avg_fill_price = ?,
                       version = version + 1, updated_at = ?
                 WHERE id = ?
                """,
                status.name(),
                filledQuantity,
                avgFillPrice,
                Timestamp.from(updatedAt),
                orderId);
    }

    public long nextSequenceNo(UUID orderId) {
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM order_events WHERE order_id = ?", Long.class, orderId);
        return (current == null ? 0L : current) + 1L;
    }

    /**
     * Appends an event. There is no corresponding update or delete, by design: {@code order_events} is
     * append-only in the database, and an API that could not express a rewrite is the honest mirror of
     * that.
     */
    public void appendEvent(
            UUID orderId,
            long sequenceNo,
            OrderEventType type,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            String payloadJson,
            Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO order_events (order_id, sequence_no, event_type, from_status, to_status,
                                          payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                orderId,
                sequenceNo,
                type.name(),
                fromStatus == null ? null : fromStatus.name(),
                toStatus.name(),
                payloadJson,
                Timestamp.from(occurredAt));
    }

    public List<OrderEvent> eventsOf(UUID orderId) {
        return jdbc.query(
                """
                SELECT id, order_id, sequence_no, event_type, from_status, to_status, payload, occurred_at
                  FROM order_events
                 WHERE order_id = ?
                 ORDER BY sequence_no
                """,
                EVENT_MAPPER,
                orderId);
    }

    /**
     * Keyset pagination: {@code (created_at, id)} strictly after the cursor, newest first. No OFFSET —
     * it degrades linearly with depth and, under concurrent inserts, skips or repeats rows.
     */
    public List<Order> page(String symbol, OrderStatus status, OrderPageCursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + ORDER_COLUMNS + " FROM orders WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();

        if (symbol != null) {
            sql.append(" AND symbol = ?");
            arguments.add(symbol);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            arguments.add(status.name());
        }
        if (cursor != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.createdAt()));
            arguments.add(cursor.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        arguments.add(limit);

        return jdbc.query(sql.toString(), ORDER_MAPPER, arguments.toArray());
    }
}
