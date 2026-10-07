package io.github.joyen09.exchangecore.order;

import java.util.UUID;

/** Order-related failures the API maps to specific problem codes. */
public final class OrderExceptions {

    private OrderExceptions() {}

    /** The symbol is not in the symbol table. Refused rather than inferred from the string. */
    public static class UnknownSymbolException extends RuntimeException {
        public UnknownSymbolException(String symbol) {
            super("unknown symbol: " + symbol);
        }
    }

    /**
     * Phase 2 cannot price a market order, so it cannot know what to lock. Refusing is the honest
     * answer; inventing a price would not be.
     */
    public static class MarketOrderNotSupportedException extends RuntimeException {
        public MarketOrderNotSupportedException() {
            super("market orders are not supported yet: the funds to lock cannot be computed without a price");
        }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID orderId) {
            super("no order with id " + orderId);
        }
    }

    /** Cancelling something already finished. Distinct from an illegal transition: it is a client error. */
    public static class OrderAlreadyTerminalException extends RuntimeException {
        private final OrderStatus status;

        public OrderAlreadyTerminalException(UUID orderId, OrderStatus status) {
            super("order %s is already %s".formatted(orderId, status));
            this.status = status;
        }

        public OrderStatus status() {
            return status;
        }
    }

    /** A validation failure in the request body itself. */
    public static class InvalidOrderException extends RuntimeException {
        public InvalidOrderException(String message) {
            super(message);
        }
    }
}
