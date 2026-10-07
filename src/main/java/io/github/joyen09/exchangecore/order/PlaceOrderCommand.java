package io.github.joyen09.exchangecore.order;

import java.math.BigDecimal;

/** A request to place an order, already parsed but not yet validated against the symbol table. */
public record PlaceOrderCommand(
        String ownerId,
        String clientOrderId,
        String symbol,
        OrderSide side,
        OrderType type,
        TimeInForce timeInForce,
        BigDecimal quantity,
        BigDecimal price) {}
