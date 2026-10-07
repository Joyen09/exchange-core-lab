package io.github.joyen09.exchangecore.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns a request body into a stable fingerprint, so "same key, same request" can be told from "same
 * key, different request".
 *
 * <p>Two bodies that mean the same thing must hash the same: field order must not matter, and
 * {@code 1.50} must match {@code 1.5}.
 *
 * <h2>The trade-off worth knowing about</h2>
 *
 * Amounts cross this API as JSON <em>strings</em>, to keep clients away from binary floating point. So
 * numeric normalisation cannot simply be applied to every string that happens to parse as a number:
 * {@code clientOrderId} {@code "001"} and {@code "1"} are different orders, and collapsing them would
 * be a correctness bug far worse than the one being avoided. Normalisation is therefore driven by
 * <em>field name</em> — the fields that are known to hold amounts — rather than by what a value looks
 * like. The cost is that a new amount-bearing field has to be added to {@link #NUMERIC_FIELDS}, and
 * forgetting to do so makes the hash stricter rather than looser, which is the safe direction.
 */
@Component
public class CanonicalRequest {

    /** Fields whose values are amounts, and so compare numerically rather than as text. */
    static final Set<String> NUMERIC_FIELDS = Set.of("quantity", "price");

    private final ObjectMapper json;

    public CanonicalRequest(ObjectMapper json) {
        this.json = json;
    }

    /** @return lowercase hex SHA-256 of the canonical form, 64 characters */
    public String fingerprint(String body) {
        return sha256(canonicalise(body));
    }

    String canonicalise(String body) {
        JsonNode parsed;
        try {
            parsed = json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            // A body that is not JSON still needs a stable fingerprint; its bytes are the canonical form.
            return body == null ? "" : body;
        }
        StringBuilder out = new StringBuilder();
        write(parsed, null, out);
        return out.toString();
    }

    private void write(JsonNode node, String fieldName, StringBuilder out) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                String name = names.get(i);
                out.append('"').append(name).append("\":");
                write(node.get(name), name, out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), fieldName, out);
            }
            out.append(']');
            return;
        }
        if (node.isNumber()) {
            out.append(normaliseNumber(node.decimalValue()));
            return;
        }
        if (node.isTextual() && fieldName != null && NUMERIC_FIELDS.contains(fieldName)) {
            try {
                out.append('"').append(normaliseNumber(new BigDecimal(node.asText()))).append('"');
                return;
            } catch (NumberFormatException notANumber) {
                // Falls through to plain text: a malformed amount is a validation problem, not a
                // fingerprinting one, and must not be silently rewritten here.
            }
        }
        if (node.isTextual()) {
            out.append('"').append(node.asText()).append('"');
            return;
        }
        out.append(node.isNull() ? "null" : node.asText());
    }

    private static String normaliseNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        // stripTrailingZeros turns 100 into 1E+2; plain text keeps the fingerprint readable and stable.
        return stripped.toPlainString();
    }

    private static String sha256(String canonical) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK and must be present", e);
        }
        byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** Exposed for the unit test that pins the normalisation rules. */
    Map<String, Set<String>> rules() {
        return Map.of("numericFields", NUMERIC_FIELDS);
    }
}
