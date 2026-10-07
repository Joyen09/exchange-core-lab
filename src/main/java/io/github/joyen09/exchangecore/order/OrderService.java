package io.github.joyen09.exchangecore.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joyen09.exchangecore.order.OrderExceptions.InvalidOrderException;
import io.github.joyen09.exchangecore.order.OrderExceptions.MarketOrderNotSupportedException;
import io.github.joyen09.exchangecore.order.OrderExceptions.OrderAlreadyTerminalException;
import io.github.joyen09.exchangecore.order.OrderExceptions.OrderNotFoundException;
import io.github.joyen09.exchangecore.order.OrderExceptions.UnknownSymbolException;
import io.github.joyen09.exchangecore.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order write path.
 *
 * <p>Every state change goes through {@link #transition}, and that method does all six steps of SPEC
 * §2.3 in one transaction: lock the row, verify the transition against the table, append the event,
 * update the projection, append to the outbox, apply the ledger effect. The outbox write sharing the
 * transaction is the whole point of the pattern — if it could be separated, a committed state change
 * could exist with no event, or an event with no state change.
 *
 * <p>Transactions are programmatic rather than {@code @Transactional}, as in the ledger: the boundary
 * is the subject, so it is written down rather than implied by a proxy.
 */
@Service
public class OrderService {

    private static final String AGGREGATE_TYPE = "ORDER";

    private final OrderRepository repository;
    private final OutboxRepository outbox;
    private final OrderFundsLock fundsLock;
    private final OrderMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectMapper json;

    public OrderService(
            OrderRepository repository,
            OutboxRepository outbox,
            OrderFundsLock fundsLock,
            OrderMetrics metrics,
            TransactionTemplate transactions,
            Clock clock,
            ObjectMapper json) {
        this.repository = repository;
        this.outbox = outbox;
        this.fundsLock = fundsLock;
        this.metrics = metrics;
        this.transactions = transactions;
        this.clock = clock;
        this.json = json;
    }

    /** Whether a placement created the order or found one with the same {@code clientOrderId}. */
    public record Placement(Order order, boolean created) {}

    /**
     * Creates an order and locks the funds it needs.
     *
     * <p>Running out of funds is not a failed request: the order is created and immediately rejected,
     * with the reason in the event payload. It never sits in {@code PENDING} waiting for money.
     */
    public Placement place(PlaceOrderCommand command) {
        validate(command);

        return transactions.execute(status -> {
            TradingSymbol symbol = repository
                    .findSymbol(command.symbol())
                    .orElseThrow(() -> new UnknownSymbolException(command.symbol()));

            Instant now = clock.instant();
            Order candidate = new Order(
                    UUID.randomUUID(),
                    command.ownerId(),
                    command.clientOrderId(),
                    command.symbol(),
                    command.side(),
                    command.type(),
                    command.timeInForce(),
                    FillArithmetic.normalise(command.quantity()),
                    FillArithmetic.normalise(command.price()),
                    BigDecimal.ZERO,
                    null,
                    OrderStatus.PENDING,
                    0L,
                    now,
                    now);

            if (!repository.insertIfAbsent(candidate)) {
                // The domain-level guard: this business order already exists, whatever Idempotency-Key
                // the caller used this time. Return it rather than creating a second one (ADR-0007).
                Order existing = repository
                        .findByClientOrderId(command.ownerId(), command.clientOrderId())
                        .orElseThrow(() -> new IllegalStateException(
                                "client_order_id %s conflicted for owner %s but no order was found"
                                        .formatted(command.clientOrderId(), command.ownerId())));
                return new Placement(existing, false);
            }

            appendEvent(candidate.id(), OrderEventType.ORDER_CREATED, null, OrderStatus.PENDING, createdPayload(candidate), now);
            metrics.transitioned(null, OrderStatus.PENDING);

            if (!fundsLock.tryLock(candidate, symbol)) {
                Order rejected = transitionLocked(
                        candidate.id(),
                        OrderStatus.REJECTED,
                        OrderEventType.ORDER_REJECTED,
                        null,
                        Map.of(
                                "reason", "INSUFFICIENT_BALANCE",
                                "asset", fundsLock.assetToLock(candidate, symbol),
                                "required", fundsLock.amountToLock(candidate).toPlainString()),
                        FundsEffect.NONE,
                        symbol);
                return new Placement(rejected, true);
            }

            return new Placement(
                    repository.findById(candidate.id()).orElseThrow(), true);
        });
    }

    /**
     * Requests cancellation.
     *
     * <p>A {@code PENDING} order was never sent anywhere, so it cancels outright. Anything working at a
     * venue can only be <em>asked</em> to cancel, which is what {@code CANCELING} means. Asking twice
     * emits no second event — the repeat is absorbed here rather than becoming a transition.
     */
    public Order requestCancel(UUID orderId) {
        return transactions.execute(status -> {
            Order current = repository
                    .lockForTransition(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId));

            if (current.status().isTerminal()) {
                throw new OrderAlreadyTerminalException(orderId, current.status());
            }
            if (current.status() == OrderStatus.CANCELING) {
                return current;
            }
            if (current.status() == OrderStatus.PENDING) {
                return transition(orderId, OrderStatus.CANCELED, OrderEventType.ORDER_CANCELED, null, Map.of(), FundsEffect.RELEASE);
            }
            return transition(
                    orderId, OrderStatus.CANCELING, OrderEventType.ORDER_CANCEL_REQUESTED, null, Map.of(), FundsEffect.NONE);
        });
    }

    /** The venue accepted the order. Phase 3 calls this from the adapter. */
    public Order submit(UUID orderId) {
        return transition(orderId, OrderStatus.SUBMITTED, OrderEventType.ORDER_SUBMITTED, null, Map.of(), FundsEffect.NONE);
    }

    /**
     * Records one execution. The resulting status is {@code FILLED} only when nothing is outstanding;
     * anything less is {@code PARTIALLY_FILLED}, including the second and third fill of the same order.
     */
    public Order recordFill(UUID orderId, Fill fill) {
        return transactions.execute(status -> {
            Order current = repository
                    .lockForTransition(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
            BigDecimal filled = current.filledQuantity().add(fill.quantity());
            if (filled.compareTo(current.quantity()) > 0) {
                throw new InvalidOrderException("fill of %s would overfill order %s (%s of %s already filled)"
                        .formatted(
                                fill.quantity().toPlainString(),
                                orderId,
                                current.filledQuantity().toPlainString(),
                                current.quantity().toPlainString()));
            }
            boolean complete = filled.compareTo(current.quantity()) == 0;
            return transition(
                    orderId,
                    complete ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    complete ? OrderEventType.ORDER_FILLED : OrderEventType.ORDER_PARTIALLY_FILLED,
                    fill,
                    // The fill's own fields are added by the transition itself, so every entry point
                    // produces the same event shape.
                    Map.of(),
                    // Settlement is Phase 3; a fill moves no value yet, so nothing is released here.
                    FundsEffect.NONE);
        });
    }

    /**
     * Confirms a cancellation.
     *
     * <p>Phase 2 has no venue, so this stands in for the venue's confirmation and is what the tests
     * call. There is deliberately no HTTP endpoint for it: an endpoint that forces a state change is a
     * back door, and this repository's whole guardrail story is that controls have no override.
     */
    public Order confirmCancel(UUID orderId) {
        return transition(orderId, OrderStatus.CANCELED, OrderEventType.ORDER_CANCELED, null, Map.of(), FundsEffect.RELEASE);
    }

    public Order expire(UUID orderId) {
        return transition(orderId, OrderStatus.EXPIRED, OrderEventType.ORDER_EXPIRED, null, Map.of(), FundsEffect.RELEASE);
    }

    /** Rejection after the funds were locked — the venue refused it. Releases the lock. */
    public Order reject(UUID orderId, String reason) {
        return transition(
                orderId,
                OrderStatus.REJECTED,
                OrderEventType.ORDER_REJECTED,
                null,
                Map.of("reason", reason),
                FundsEffect.RELEASE);
    }

    /** A transition driven from outside, for tests and for Phase 3's adapter. */
    public Order transition(
            UUID orderId,
            OrderStatus to,
            OrderEventType eventType,
            Fill fill,
            Map<String, Object> payload,
            FundsEffect fundsEffect) {
        return transactions.execute(status -> {
            TradingSymbol symbol = null;
            if (fundsEffect == FundsEffect.RELEASE) {
                Order order = repository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
                symbol = repository.findSymbol(order.symbol()).orElseThrow(() -> new UnknownSymbolException(order.symbol()));
            }
            return transitionLocked(orderId, to, eventType, fill, payload, fundsEffect, symbol);
        });
    }

    private Order transitionLocked(
            UUID orderId,
            OrderStatus to,
            OrderEventType eventType,
            Fill fill,
            Map<String, Object> payload,
            FundsEffect fundsEffect,
            TradingSymbol symbol) {

        // 1. Lock the row. Everything below depends on no one else moving this order meanwhile.
        Order current = repository.lockForTransition(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        // 2. Verify against the table. An illegal transition throws and takes the transaction with it,
        //    so no event and no outbox row survive — which §7.1 asserts.
        if (!OrderStatusTransitions.isLegal(current.status(), to)) {
            metrics.rejected(current.status(), to);
            throw new IllegalStateTransitionException(orderId, current.status(), to);
        }

        BigDecimal filledQuantity = current.filledQuantity();
        BigDecimal avgFillPrice = current.avgFillPrice();
        if (fill != null) {
            BigDecimal previousNotional = current.avgFillPrice() == null
                    ? BigDecimal.ZERO
                    : current.avgFillPrice().multiply(current.filledQuantity());
            filledQuantity = current.filledQuantity().add(fill.quantity());
            avgFillPrice = FillArithmetic.averagePrice(previousNotional.add(fill.notional()), filledQuantity);
        }

        Instant now = clock.instant();
        Map<String, Object> fullPayload = new LinkedHashMap<>(payload);
        if (fill != null) {
            // Written here rather than left to the caller. The projector folds the log from these two
            // fields, so an event that omitted them would rebuild a different order from the one that
            // was stored — and only for whichever entry point forgot. The property test in §7.4 found
            // exactly that.
            fullPayload.put("fillQuantity", fill.quantity().toPlainString());
            fullPayload.put("fillPrice", fill.price().toPlainString());
            fullPayload.put("filledQuantity", filledQuantity.toPlainString());
            fullPayload.put("avgFillPrice", avgFillPrice == null ? null : avgFillPrice.toPlainString());
        }

        // 3, 4, 5: event, projection, outbox — one transaction, no ordering subtlety between them.
        long sequenceNo = appendEvent(orderId, eventType, current.status(), to, fullPayload, now);
        repository.updateProjection(orderId, to, FillArithmetic.normalise(filledQuantity), avgFillPrice, now);

        // 6. Ledger effect. Explicitly chosen by the caller rather than inferred from the target state:
        //    a rejection for insufficient funds must not release a lock that was never taken.
        if (fundsEffect == FundsEffect.RELEASE) {
            Order order = repository.findById(orderId).orElseThrow();
            fundsLock.release(order, symbol);
        }

        metrics.transitioned(current.status(), to);
        return repository.findById(orderId).orElseThrow();
    }

    private long appendEvent(
            UUID orderId,
            OrderEventType type,
            OrderStatus from,
            OrderStatus to,
            Map<String, Object> payload,
            Instant occurredAt) {
        long sequenceNo = repository.nextSequenceNo(orderId);
        String payloadJson = serialise(payload);
        repository.appendEvent(orderId, sequenceNo, type, from, to, payloadJson, occurredAt);

        UUID eventId = UUID.randomUUID();
        outbox.append(
                AGGREGATE_TYPE,
                orderId,
                eventId,
                type.name(),
                serialise(envelope(eventId, orderId, sequenceNo, type, from, to, payload, occurredAt)),
                occurredAt);
        return sequenceNo;
    }

    private Map<String, Object> envelope(
            UUID eventId,
            UUID orderId,
            long sequenceNo,
            OrderEventType type,
            OrderStatus from,
            OrderStatus to,
            Map<String, Object> payload,
            Instant occurredAt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("aggregateType", AGGREGATE_TYPE);
        envelope.put("aggregateId", orderId.toString());
        envelope.put("sequenceNo", sequenceNo);
        envelope.put("eventType", type.name());
        envelope.put("fromStatus", from == null ? null : from.name());
        envelope.put("toStatus", to.name());
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("payload", payload);
        return envelope;
    }

    private Map<String, Object> createdPayload(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // Everything immutable about the order lives here, because the projector rebuilds from events
        // alone and must not need the row it is checking against.
        payload.put("ownerId", order.ownerId());
        payload.put("clientOrderId", order.clientOrderId());
        payload.put("symbol", order.symbol());
        payload.put("side", order.side().name());
        payload.put("type", order.type().name());
        payload.put("timeInForce", order.timeInForce() == null ? null : order.timeInForce().name());
        payload.put("quantity", order.quantity().toPlainString());
        payload.put("price", order.price() == null ? null : order.price().toPlainString());
        return payload;
    }

    private String serialise(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Not recoverable and not ignorable: an event we cannot serialise is an event we cannot
            // record, and the transition must not appear to have happened.
            throw new IllegalStateException("could not serialise an order event payload", e);
        }
    }

    private static void validate(PlaceOrderCommand command) {
        if (command.type() == OrderType.MARKET) {
            throw new MarketOrderNotSupportedException();
        }
        if (command.price() == null) {
            throw new InvalidOrderException("a LIMIT order needs a price");
        }
        if (command.timeInForce() == null) {
            throw new InvalidOrderException("a LIMIT order needs a timeInForce");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new InvalidOrderException("quantity must be greater than zero");
        }
        if (command.price().signum() <= 0) {
            throw new InvalidOrderException("price must be greater than zero");
        }
    }

    /** Reads, for the API. No lock and no transaction: a read of an order cannot be acted on blindly. */
    public Order require(UUID orderId) {
        return repository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public java.util.List<Order> page(String symbol, OrderStatus status, OrderPageCursor cursor, int limit) {
        return repository.page(symbol, status, cursor, limit);
    }

    /** Whether a transition returns locked funds. Chosen by the caller, never inferred. */
    public enum FundsEffect {
        NONE,
        RELEASE
    }
}
