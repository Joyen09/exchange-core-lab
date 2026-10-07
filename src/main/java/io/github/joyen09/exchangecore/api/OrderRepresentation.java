package io.github.joyen09.exchangecore.api;

import io.github.joyen09.exchangecore.order.Order;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON representations.
 *
 * <p>Every amount is a <b>string</b>, never a JSON number. A JSON number invites the client to parse it
 * into a double, and a double cannot hold {@code 0.1} — which is the kind of defect that shows up as a
 * one-satoshi discrepancy months later.
 */
final class OrderRepresentation {

    private OrderRepresentation() {}

    static Map<String, Object> of(Order order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", order.id().toString());
        body.put("clientOrderId", order.clientOrderId());
        body.put("symbol", order.symbol());
        body.put("side", order.side().name());
        body.put("type", order.type().name());
        body.put("timeInForce", order.timeInForce() == null ? null : order.timeInForce().name());
        body.put("quantity", text(order.quantity()));
        body.put("price", text(order.price()));
        body.put("filledQuantity", text(order.filledQuantity()));
        body.put("avgFillPrice", text(order.avgFillPrice()));
        body.put("status", order.status().name());
        body.put("version", order.version());
        body.put("createdAt", order.createdAt().toString());
        body.put("updatedAt", order.updatedAt().toString());
        return body;
    }

    /**
     * A successful response that the caller should nonetheless notice — currently only a duplicate
     * {@code clientOrderId}. Kept as its own object rather than smuggled into the order fields, and
     * absent entirely when there is nothing to say.
     */
    static Map<String, Object> withNotice(Order order, String code, String detail) {
        Map<String, Object> body = of(order);
        body.put("notice", Map.of("code", code, "detail", detail));
        return body;
    }

    static Map<String, Object> page(List<Order> orders, String nextCursor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", orders.stream().map(OrderRepresentation::of).toList());
        body.put("nextCursor", nextCursor);
        return body;
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
