package io.github.joyen09.exchangecore.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The verification consumer (SPEC §5.3).
 *
 * <p>It exists to demonstrate that at-least-once delivery plus de-duplication adds up to exactly-once
 * <em>effects</em>, which is the claim the outbox pattern actually makes. It deliberately has no
 * business side effects: nothing downstream depends on it, so it can be read as evidence rather than as
 * machinery.
 *
 * <p>Two things it checks, both of which are supposed to be impossible and one of which is only
 * improbable:
 *
 * <ul>
 *   <li><b>Duplicates</b> — expected. The publisher can send a message and die before marking it, so
 *       re-delivery is normal, and {@code event_id} is what makes the second one harmless.
 *   <li><b>Out-of-order sequence numbers per order</b> — should never happen, because the partition key
 *       is the order id and one partition preserves order. If the counter ever moves, either the key
 *       changed or the topic was repartitioned, and that is worth knowing before it causes a
 *       reconciliation break in Phase 4.
 * </ul>
 */
@Component
public class OrderEventVerifier {

    private static final Logger log = LoggerFactory.getLogger(OrderEventVerifier.class);

    private final ProcessedEventRepository repository;
    private final ObjectMapper json;
    private final Clock clock;
    private final Counter processed;
    private final Counter duplicates;
    private final Counter outOfOrder;

    public OrderEventVerifier(
            ProcessedEventRepository repository, ObjectMapper json, Clock clock, MeterRegistry registry) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        this.processed = Counter.builder("consumer_events_processed_total")
                .description("Events applied for the first time")
                .register(registry);
        this.duplicates = Counter.builder("consumer_duplicates_total")
                .description("Re-deliveries suppressed by event_id — expected, not an error")
                .register(registry);
        this.outOfOrder = Counter.builder("consumer_out_of_order_total")
                .description("Events seen with a sequence_no at or below one already processed")
                .register(registry);
    }

    @KafkaListener(
            topics = "${outbox.topic:exchange-core-lab.orders.v1}",
            groupId = "${outbox.consumer-group:exchange-core-lab.verifier}")
    public void onMessage(String payload) {
        ingest(payload);
    }

    /**
     * Processes one event.
     *
     * <p>Public and separate from the listener so tests can drive it directly, without needing a broker
     * to reproduce a duplicate or an out-of-order delivery.
     *
     * @return whether this was the first time the event was seen
     */
    public boolean ingest(String payload) {
        JsonNode envelope;
        try {
            envelope = json.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Not retried and not hidden: a message we cannot parse will not parse next time either.
            log.error("skipping an unparseable event: {}", payload, e);
            return false;
        }

        UUID eventId = UUID.fromString(envelope.get("eventId").asText());
        UUID aggregateId = UUID.fromString(envelope.get("aggregateId").asText());
        Long sequenceNo = envelope.hasNonNull("sequenceNo") ? envelope.get("sequenceNo").asLong() : null;

        if (sequenceNo != null) {
            // Checked before recording, so the comparison is against events genuinely processed earlier.
            repository.highestSequenceFor(aggregateId).ifPresent(highest -> {
                if (sequenceNo <= highest) {
                    outOfOrder.increment();
                    log.warn(
                            "event {} for order {} has sequence {} but {} was already processed",
                            eventId,
                            aggregateId,
                            sequenceNo,
                            highest);
                }
            });
        }

        if (!repository.recordIfNew(eventId, aggregateId, sequenceNo, clock.instant())) {
            duplicates.increment();
            return false;
        }
        processed.increment();
        return true;
    }
}
