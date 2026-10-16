package io.github.joyen09.exchangecore.fakevenue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A response built from a fixture, which cannot contain a field the fixture does not have.
 *
 * <h2>What this class is for</h2>
 *
 * A mock server invents its responses. This one invents no bytes: it starts from a recorded payload
 * and substitutes <em>values</em> for the fields that depend on the order's state. It never decides
 * which fields exist or what they are called, because those are the facts a double can get wrong in the
 * same way the adapter gets them wrong, leaving the tests green and the production code broken
 * (ADR-0009, "Shape is closable").
 *
 * <p>That distinction is worth nothing as a statement of intent, so it is enforced: {@link #with}
 * throws if the key is not already in the fixture. A field cannot be added by accident while chasing a
 * failing test, and the failure arrives at the line that tried rather than as a mysterious extra
 * property three layers away.
 *
 * <p>The converse — a fixture field the venue never fills — is reported by {@link #untouched()}, so a
 * test can assert that every state-dependent field is actually being set rather than quietly left at
 * whatever the recorded order happened to contain.
 */
public final class VenueResponse {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectNode body;
    private final Set<String> assigned = new LinkedHashSet<>();

    private VenueResponse(ObjectNode body) {
        this.body = body;
    }

    /** Starts from a recorded payload. The copy is deep, so fixtures are never mutated. */
    public static VenueResponse from(JsonNode fixture) {
        if (!fixture.isObject()) {
            throw new IllegalArgumentException("a response is built from an object fixture");
        }
        return new VenueResponse(((ObjectNode) fixture).deepCopy());
    }

    /**
     * Substitutes a value for a field the fixture already has.
     *
     * @throws IllegalArgumentException if the field is not in the fixture — which means either the
     *     fixture is behind the venue, or this code is inventing a field. Both are worth stopping for,
     *     and neither should be settled by writing the field anyway.
     */
    public VenueResponse with(String field, Object value) {
        if (!body.has(field)) {
            throw new IllegalArgumentException(("""
                    the fixture has no field '%s', so the fake venue would be inventing one.

                    Either the recorded payload is out of date — re-capture it and commit the diff on \
                    its own, citing the source line — or this code is making up a field Binance does \
                    not send. Adding it here would make the test pass and the adapter wrong.

                    Fields the fixture does have: %s""")
                    .formatted(field, fieldNames()));
        }
        body.set(field, JSON.valueToTree(value));
        assigned.add(field);
        return this;
    }

    /** Replaces an array-valued field, element by element, under the same no-new-fields rule. */
    public VenueResponse withArray(String field, Iterable<VenueResponse> elements) {
        if (!body.has(field)) {
            throw new IllegalArgumentException("the fixture has no array field '" + field + "'");
        }
        ArrayNode array = JSON.createArrayNode();
        elements.forEach(element -> array.add(element.body));
        body.set(field, array);
        assigned.add(field);
        return this;
    }

    /** Fields the fixture carries that nothing has assigned — candidates for being stale sample data. */
    public Set<String> untouched() {
        Set<String> remaining = new LinkedHashSet<>();
        body.fieldNames().forEachRemaining(remaining::add);
        remaining.removeAll(assigned);
        return remaining;
    }

    public ObjectNode node() {
        return body;
    }

    public String json() {
        return body.toString();
    }

    private String fieldNames() {
        StringBuilder names = new StringBuilder();
        body.fieldNames().forEachRemaining(name -> names.append(names.isEmpty() ? "" : ", ").append(name));
        return names.toString();
    }
}
