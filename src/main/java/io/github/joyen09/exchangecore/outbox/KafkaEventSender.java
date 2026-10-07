package io.github.joyen09.exchangecore.outbox;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** The real sender. Blocks for the acknowledgement, with a timeout, and never swallows a failure. */
@Component
public class KafkaEventSender implements EventSender {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaEventSender(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void send(String topic, String key, String payload, Duration timeout) {
        try {
            kafkaTemplate.send(topic, key, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventSendFailedException("interrupted while publishing to " + topic, e);
        } catch (ExecutionException e) {
            throw new EventSendFailedException("broker rejected the message for " + topic, e.getCause());
        } catch (TimeoutException e) {
            throw new EventSendFailedException(
                    "no acknowledgement from the broker for %s within %s".formatted(topic, timeout), e);
        }
    }

    /** Never caught and ignored: the publisher turns it into a retry with backoff, or a dead row. */
    public static class EventSendFailedException extends RuntimeException {
        public EventSendFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
