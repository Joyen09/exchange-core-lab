package io.github.joyen09.exchangecore.fakevenue;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What the venue does and when — the half of a test double that a recording cannot provide.
 *
 * <p>The division is deliberate and is the whole of ADR-0009's position on record-and-replay.
 * Recordings were rejected as a source of <em>scenarios</em>, because no recording can be made of a
 * socket dropping after the second fill. They were adopted as a source of <em>bytes</em>, because two
 * implementations each deriving the wire format from one reading is how a shared misunderstanding
 * becomes invisible. Scripted timing, recorded bytes.
 *
 * <p>So this class contains no JSON and no field names. It says that the third attempt at placing an
 * order should fail with 503, or that a fill of 0.1 happens and then the socket goes away. What those
 * events look like on the wire is {@link VenueResponse}'s problem, and it takes the answer from a
 * fixture.
 *
 * <h2>Determinism</h2>
 *
 * Every script carries a seed, and the same seed must produce the same sequence of events. A chaos
 * test that cannot be re-run identically is not a test; it is an anecdote that will be retried until
 * it passes. {@code FakeVenueDeterminismTest} runs one script twice and compares the sequences element
 * by element.
 */
public final class VenueScript {

    /** One thing the venue does, in order. The vocabulary is events, never payloads. */
    public sealed interface Step {

        /** A fill of this quantity at this price. */
        record Fill(BigDecimal quantity, BigDecimal price) implements Step {}

        /** The user data stream goes away at this point in the sequence. */
        record DisconnectStream() implements Step {}

        /** The same execution report is delivered twice. */
        record RedeliverPrevious() implements Step {}

        /** The next two events are delivered in the opposite order. */
        record SwapNextTwo() implements Step {}
    }

    private final long seed;
    private final Map<String, List<Step>> stepsByClientOrderId;
    private final Map<String, Deque<Integer>> httpFailures;
    private final int weightLimit;

    private VenueScript(Builder builder) {
        this.seed = builder.seed;
        this.stepsByClientOrderId = Map.copyOf(builder.steps);
        this.httpFailures = new LinkedHashMap<>();
        builder.failures.forEach((route, codes) -> httpFailures.put(route, new ArrayDeque<>(codes)));
        this.weightLimit = builder.weightLimit;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A fresh generator for this script's seed, so two runs of one script draw the same numbers. */
    public Random random() {
        return new Random(seed);
    }

    public long seed() {
        return seed;
    }

    public List<Step> stepsFor(String clientOrderId) {
        return stepsByClientOrderId.getOrDefault(clientOrderId, List.of());
    }

    public int weightLimit() {
        return weightLimit;
    }

    /**
     * The status the next call to this route should fail with, if the script says so.
     *
     * <p>Consuming rather than peeking: {@code times(2)} means the first two attempts fail and the
     * third succeeds, which is acceptance criterion 4's scenario and would be a different test
     * entirely if the failures repeated forever.
     */
    public synchronized Integer nextFailureFor(String route) {
        Deque<Integer> queued = httpFailures.get(route);
        return queued == null || queued.isEmpty() ? null : queued.poll();
    }

    public static final class Builder {

        private long seed = 0L;
        private final Map<String, List<Step>> steps = new LinkedHashMap<>();
        private final Map<String, List<Integer>> failures = new LinkedHashMap<>();
        private int weightLimit = 6000;

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        /** What happens to the order with this client order id, in order. */
        public Builder onOrder(String clientOrderId, Step... script) {
            steps.put(clientOrderId, List.of(script));
            return this;
        }

        /**
         * The next {@code times} calls to this route fail with {@code status}.
         *
         * @param route as the server routes it, e.g. {@code "POST /api/v3/order"}
         */
        public Builder httpFailure(String route, int times, int status) {
            List<Integer> queued = new ArrayList<>(failures.getOrDefault(route, List.of()));
            for (int i = 0; i < times; i++) {
                queued.add(status);
            }
            failures.put(route, queued);
            return this;
        }

        /** The REQUEST_WEIGHT budget the venue reports and enforces. */
        public Builder weightLimit(int weightLimit) {
            this.weightLimit = weightLimit;
            return this;
        }

        public VenueScript build() {
            return new VenueScript(this);
        }
    }

    /** Shorthand so a script reads as a sequence of events rather than a pile of constructors. */
    public static Step fill(String quantity, String price) {
        return new Step.Fill(new BigDecimal(quantity), new BigDecimal(price));
    }

    public static Step disconnectStream() {
        return new Step.DisconnectStream();
    }

    public static Step redeliverPrevious() {
        return new Step.RedeliverPrevious();
    }

    public static Step swapNextTwo() {
        return new Step.SwapNextTwo();
    }
}
