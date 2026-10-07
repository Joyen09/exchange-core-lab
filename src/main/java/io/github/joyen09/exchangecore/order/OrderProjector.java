package io.github.joyen09.exchangecore.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joyen09.exchangecore.order.OrderExceptions.OrderNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Rebuilds an order from its event log.
 *
 * <p>This is what makes the claim in ADR-0008 checkable rather than decorative: if {@code orders} is a
 * projection, then folding the log must produce it exactly, and a property test over 500 random event
 * sequences asserts that field for field. Without this method the claim would be untestable, and an
 * untestable claim about the source of truth is just a comment.
 *
 * <p>Fills are recomputed from each event's own {@code fillQuantity} and {@code fillPrice} rather than
 * read back from the running totals the write path also stored — otherwise the fold would be checking
 * the write path's arithmetic against itself.
 */
@Component
public class OrderProjector {

    private final OrderRepository repository;
    private final ObjectMapper json;

    public OrderProjector(OrderRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    public Order rebuild(UUID orderId) {
        List<OrderEvent> events = repository.eventsOf(orderId);
        if (events.isEmpty()) {
            throw new OrderNotFoundException(orderId);
        }

        OrderEvent created = events.getFirst();
        if (created.type() != OrderEventType.ORDER_CREATED) {
            throw new IllegalStateException(
                    "order %s begins with %s, not ORDER_CREATED".formatted(orderId, created.type()));
        }
        JsonNode genesis = parse(created.payload());

        BigDecimal filledQuantity = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        for (OrderEvent event : events) {
            JsonNode payload = parse(event.payload());
            if (payload.hasNonNull("fillQuantity") && payload.hasNonNull("fillPrice")) {
                BigDecimal quantity = new BigDecimal(payload.get("fillQuantity").asText());
                BigDecimal price = new BigDecimal(payload.get("fillPrice").asText());
                filledQuantity = filledQuantity.add(quantity);
                notional = notional.add(quantity.multiply(price));
            }
        }

        OrderEvent last = events.getLast();
        return new Order(
                orderId,
                genesis.get("ownerId").asText(),
                genesis.get("clientOrderId").asText(),
                genesis.get("symbol").asText(),
                OrderSide.valueOf(genesis.get("side").asText()),
                OrderType.valueOf(genesis.get("type").asText()),
                genesis.hasNonNull("timeInForce") ? TimeInForce.valueOf(genesis.get("timeInForce").asText()) : null,
                FillArithmetic.normalise(new BigDecimal(genesis.get("quantity").asText())),
                genesis.hasNonNull("price")
                        ? FillArithmetic.normalise(new BigDecimal(genesis.get("price").asText()))
                        : null,
                FillArithmetic.normalise(filledQuantity),
                FillArithmetic.averagePrice(notional, filledQuantity),
                last.toStatus(),
                // Creation is event 1 and does not bump the version; every later transition does.
                events.size() - 1L,
                created.occurredAt(),
                last.occurredAt());
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("an order event payload is not valid JSON: " + payload, e);
        }
    }
}
