package io.github.joyen09.exchangecore.order;

import java.math.BigDecimal;
import java.util.Objects;

/** A single execution against an order: how much, at what price. */
public record Fill(BigDecimal quantity, BigDecimal price) {

    public Fill {
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(price, "price");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive, got " + quantity.toPlainString());
        }
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("fill price must be positive, got " + price.toPlainString());
        }
    }

    public BigDecimal notional() {
        return quantity.multiply(price);
    }
}
