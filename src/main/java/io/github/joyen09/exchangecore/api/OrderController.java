package io.github.joyen09.exchangecore.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joyen09.exchangecore.api.ApiExceptions.FieldError;
import io.github.joyen09.exchangecore.api.ApiExceptions.ValidationFailedException;
import io.github.joyen09.exchangecore.idempotency.IdempotencyExceptions.KeyRequiredException;
import io.github.joyen09.exchangecore.idempotency.IdempotentRequests;
import io.github.joyen09.exchangecore.order.Order;
import io.github.joyen09.exchangecore.order.OrderPageCursor;
import io.github.joyen09.exchangecore.order.OrderService;
import io.github.joyen09.exchangecore.order.OrderSide;
import io.github.joyen09.exchangecore.order.OrderStatus;
import io.github.joyen09.exchangecore.order.OrderType;
import io.github.joyen09.exchangecore.order.PlaceOrderCommand;
import io.github.joyen09.exchangecore.order.TimeInForce;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order API.
 *
 * <p>The request body is taken as a raw string rather than a bound DTO, because the idempotency layer
 * fingerprints what the client actually sent. Binding first and re-serialising would fingerprint the
 * server's idea of the request, and two bodies that differ only in whitespace would then be told they
 * are the same request — which they are, but for the wrong reason.
 *
 * <p>Single-tenant: there is no authentication (SPEC §6), so every order belongs to one owner. The
 * column exists so that adding tenancy later is a change to this line rather than a migration over
 * live data.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final String DEFAULT_OWNER = "local";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final OrderService orders;
    private final IdempotentRequests idempotent;
    private final ObjectMapper json;

    public OrderController(OrderService orders, IdempotentRequests idempotent, ObjectMapper json) {
        this.orders = orders;
        this.idempotent = idempotent;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<String> place(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String body) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new KeyRequiredException();
        }

        IdempotentRequests.Outcome outcome = idempotent.execute(idempotencyKey, body, () -> {
            PlaceOrderCommand command = parse(body);
            OrderService.Placement placement = orders.place(command);

            Map<String, Object> representation = placement.created()
                    ? OrderRepresentation.of(placement.order())
                    : OrderRepresentation.withNotice(
                            placement.order(),
                            "DUPLICATE_CLIENT_ORDER_ID",
                            "an order with this clientOrderId already exists; the existing order is returned");

            // A rejected order is still a successfully handled request: the HTTP status describes what
            // happened to the request, and "we processed it and the answer is no" is a 201 with a
            // REJECTED body, not a 4xx (ADR-0007).
            int status = placement.created() ? HttpStatus.CREATED.value() : HttpStatus.OK.value();
            return new IdempotentRequests.Outcome(status, serialise(representation), placement.order().id());
        });

        return respond(outcome);
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> get(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(serialise(OrderRepresentation.of(orders.require(id))));
    }

    @GetMapping
    public ResponseEntity<String> list(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        List<FieldError> errors = new ArrayList<>();
        OrderStatus parsedStatus = null;
        if (status != null) {
            try {
                parsedStatus = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("status", "not a known order status: " + status));
            }
        }
        int parsedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (parsedLimit < 1 || parsedLimit > MAX_LIMIT) {
            errors.add(new FieldError("limit", "must be between 1 and " + MAX_LIMIT));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }

        List<Order> page = orders.page(
                symbol, parsedStatus, cursor == null ? null : OrderPageCursor.decode(cursor), parsedLimit);

        // A next cursor only when the page was full: an under-full page is the end of the data, and
        // handing back a cursor there would invite a pointless extra round trip.
        String nextCursor = page.size() < parsedLimit
                ? null
                : new OrderPageCursor(page.getLast().createdAt(), page.getLast().id()).encode();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(serialise(OrderRepresentation.page(page, nextCursor)));
    }

    /**
     * Requests cancellation.
     *
     * <p>200 when the order was never sent anywhere and is therefore already cancelled; 202 when the
     * cancellation has only been <em>requested</em>, which is all that can be promised once a venue is
     * involved. Repeating the request on an order already cancelling is also 202 and emits no event.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<String> cancel(@PathVariable UUID id) {
        Order order = orders.requestCancel(id);
        HttpStatus status = order.status() == OrderStatus.CANCELED ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(serialise(OrderRepresentation.of(order)));
    }

    private ResponseEntity<String> respond(IdempotentRequests.Outcome outcome) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(outcome.httpStatus()).contentType(MediaType.APPLICATION_JSON);
        if (outcome.httpStatus() == HttpStatus.CREATED.value() && outcome.resourceId() != null) {
            // Rebuilt from the stored resource id, so a replayed 201 carries the same Location as the
            // original rather than losing it.
            builder = builder.location(URI.create("/api/v1/orders/" + outcome.resourceId()));
        }
        return builder.body(outcome.responseBody());
    }

    private PlaceOrderCommand parse(String body) {
        JsonNode node;
        try {
            node = json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new ValidationFailedException(List.of(new FieldError("body", "not valid JSON")));
        }

        List<FieldError> errors = new ArrayList<>();
        String clientOrderId = text(node, "clientOrderId", errors, true);
        String symbol = text(node, "symbol", errors, true);
        OrderSide side = enumValue(node, "side", OrderSide.class, errors, true);
        OrderType type = enumValue(node, "type", OrderType.class, errors, true);
        // Deliberately optional here: a market order must be refused with
        // MARKET_ORDER_NOT_SUPPORTED_YET, not with "price is required", so the semantic check happens
        // in the service where the order type is already known.
        TimeInForce timeInForce = enumValue(node, "timeInForce", TimeInForce.class, errors, false);
        BigDecimal quantity = decimal(node, "quantity", errors, true);
        BigDecimal price = decimal(node, "price", errors, false);

        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return new PlaceOrderCommand(
                DEFAULT_OWNER, clientOrderId, symbol, side, type, timeInForce, quantity, price);
    }

    private static String text(JsonNode node, String field, List<FieldError> errors, boolean required) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            if (required) {
                errors.add(new FieldError(field, "is required"));
            }
            return null;
        }
        return node.get(field).asText();
    }

    private static <E extends Enum<E>> E enumValue(
            JsonNode node, String field, Class<E> type, List<FieldError> errors, boolean required) {
        String raw = text(node, field, errors, required);
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(field, "must be one of " + java.util.Arrays.toString(type.getEnumConstants())));
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node, String field, List<FieldError> errors, boolean required) {
        String raw = text(node, field, errors, required);
        if (raw == null) {
            return null;
        }
        try {
            // Amounts arrive as strings; parsing them here is the only place a decimal is constructed
            // from client input, and it never goes through double.
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            errors.add(new FieldError(field, "must be a decimal number sent as a string"));
            return null;
        }
    }

    private String serialise(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise a response body", e);
        }
    }
}
