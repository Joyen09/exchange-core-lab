package io.github.joyen09.exchangecore.fakevenue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The recorded payloads, loaded once.
 *
 * <p>These are the single definition of the wire format: the fake venue answers with them and the
 * adapter deserialises them, so a misunderstanding cannot hide in the gap between two implementations
 * that each derived the format from one reading (ADR-0009).
 *
 * <p>Their provenance, the edits that were needed to make the documented examples parse, and the rules
 * for changing them are in {@code src/test/resources/fakevenue/PROVENANCE.md}. The short version: a
 * fixture is evidence about the venue, not a knob to turn when a test fails.
 */
public final class VenueFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private VenueFixtures() {}

    /** The REST representation of one order, as {@code GET /api/v3/order} returns it. */
    public static JsonNode order() {
        return load("order.json");
    }

    /** A trade list, as {@code GET /api/v3/myTrades} returns it. */
    public static JsonNode trades() {
        return load("myTrades.json");
    }

    /** One {@code executionReport} from the user data stream — the {@code NEW} example. */
    public static JsonNode executionReport() {
        return load("executionReport.json");
    }

    /** The raw bytes, for tests that care what is on the wire rather than what it parses to. */
    public static String raw(String name) {
        try (InputStream in = open(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new UncheckedIOException("fixture " + name + " is unreadable", new java.io.IOException(e));
        }
    }

    private static JsonNode load(String name) {
        try (InputStream in = open(name)) {
            return JSON.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + name + " is not readable JSON", e);
        }
    }

    private static InputStream open(String name) {
        InputStream in = VenueFixtures.class.getClassLoader().getResourceAsStream("fakevenue/" + name);
        if (in == null) {
            throw new IllegalStateException("no such fixture: fakevenue/" + name);
        }
        return in;
    }
}
