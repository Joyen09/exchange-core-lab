package io.github.joyen09.exchangecore.outbox;

import java.time.Duration;

/**
 * Sends one message to the broker.
 *
 * <p>An interface rather than a direct {@code KafkaTemplate} call, because the outbox's interesting
 * behaviour is what happens when sending fails or when the process dies between the send and the
 * commit. Both need to be driven deliberately in a test; neither is reachable through a real broker
 * that is working.
 */
public interface EventSender {

    /**
     * @param key partition key — the aggregate id, so one order's events stay ordered
     * @throws RuntimeException if the message was not acknowledged within {@code timeout}
     */
    void send(String topic, String key, String payload, Duration timeout);
}
