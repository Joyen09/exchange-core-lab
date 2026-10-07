package io.github.joyen09.exchangecore.order;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Transition counters (SPEC §5.4). Rejections are counted too: a rising rate means a broken caller. */
@Component
public class OrderMetrics {

    private final MeterRegistry registry;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void transitioned(OrderStatus from, OrderStatus to) {
        registry.counter("order_transition_total", "from", name(from), "to", to.name())
                .increment();
    }

    public void rejected(OrderStatus from, OrderStatus to) {
        registry.counter("order_transition_rejected_total", "from", name(from), "to", to.name())
                .increment();
    }

    private static String name(OrderStatus status) {
        return status == null ? "NONE" : status.name();
    }
}
