package io.github.joyen09.exchangecore.support;

import io.github.joyen09.exchangecore.outbox.EventSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link EventSender} the test controls.
 *
 * <p>Exists because the outbox's two most important behaviours are unreachable through a working broker:
 * a send that fails, and a send that succeeds immediately before the process dies.
 */
public final class RecordingEventSender implements EventSender {

    public record Sent(String topic, String key, String payload) {}

    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failing = new AtomicBoolean(false);

    @Override
    public void send(String topic, String key, String payload, Duration timeout) {
        if (failing.get()) {
            throw new IllegalStateException("simulated broker outage");
        }
        sent.add(new Sent(topic, key, payload));
    }

    public void startFailing() {
        failing.set(true);
    }

    public void stopFailing() {
        failing.set(false);
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public List<String> payloads() {
        return sent().stream().map(Sent::payload).toList();
    }

    public void clear() {
        sent.clear();
    }
}
