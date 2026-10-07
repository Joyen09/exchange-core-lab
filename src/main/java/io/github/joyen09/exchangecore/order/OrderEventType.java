package io.github.joyen09.exchangecore.order;

/** The kinds of thing that can happen to an order. One per transition written to the log. */
public enum OrderEventType {
    ORDER_CREATED,
    ORDER_SUBMITTED,
    ORDER_PARTIALLY_FILLED,
    ORDER_FILLED,
    ORDER_CANCEL_REQUESTED,
    ORDER_CANCELED,
    ORDER_REJECTED,
    ORDER_EXPIRED
}
