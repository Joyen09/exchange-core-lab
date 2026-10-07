package io.github.joyen09.exchangecore.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical form, pinned.
 *
 * <p>This is the function that decides whether a repeated request is "the same request". Getting it too
 * loose means replaying a response for a different order; too strict means a client that reorders its
 * JSON fields silently places two orders. Both are worth a test each.
 */
class CanonicalRequestTest {

    private final CanonicalRequest canonical = new CanonicalRequest(new ObjectMapper());

    @Test
    @DisplayName("field order does not change the fingerprint")
    void fieldOrderIsIrrelevant() {
        String first = """
                {"clientOrderId":"a-1","symbol":"BTCUSDT","quantity":"0.5"}""";
        String second = """
                {"quantity":"0.5","symbol":"BTCUSDT","clientOrderId":"a-1"}""";

        assertThat(canonical.fingerprint(first)).isEqualTo(canonical.fingerprint(second));
    }

    @Test
    @DisplayName("whitespace does not change the fingerprint")
    void whitespaceIsIrrelevant() {
        assertThat(canonical.fingerprint("{\"symbol\":\"BTCUSDT\"}"))
                .isEqualTo(canonical.fingerprint("{\n  \"symbol\" : \"BTCUSDT\"\n}"));
    }

    @Test
    @DisplayName("1.50 and 1.5 are the same amount")
    void trailingZeroesInAmountsAreIrrelevant() {
        String padded = """
                {"quantity":"1.50","price":"60000.000"}""";
        String bare = """
                {"quantity":"1.5","price":"60000"}""";

        assertThat(canonical.fingerprint(padded)).isEqualTo(canonical.fingerprint(bare));
    }

    @Test
    @DisplayName("amounts sent as JSON numbers normalise the same way as strings")
    void numbersAndNumericStringsAgree() {
        assertThat(canonical.canonicalise("{\"quantity\":1.50}"))
                .isEqualTo(canonical.canonicalise("{\"quantity\":1.5}"));
    }

    @Test
    @DisplayName("an identifier that looks like a number is NOT normalised")
    void identifiersKeepTheirExactText() {
        // The reason normalisation is driven by field name rather than by what the value looks like:
        // "001" and "1" are different orders, and collapsing them would be a far worse bug than the
        // float-formatting one being avoided.
        assertThat(canonical.fingerprint("{\"clientOrderId\":\"001\"}"))
                .isNotEqualTo(canonical.fingerprint("{\"clientOrderId\":\"1\"}"));
    }

    @Test
    @DisplayName("a different amount is a different request")
    void differentAmountsDiffer() {
        assertThat(canonical.fingerprint("{\"quantity\":\"0.5\"}"))
                .isNotEqualTo(canonical.fingerprint("{\"quantity\":\"0.6\"}"));
    }

    @Test
    @DisplayName("nested objects and arrays are canonicalised too")
    void nestingIsHandled() {
        String first = """
                {"a":{"y":1,"x":2},"list":[{"q":1,"p":2}]}""";
        String second = """
                {"list":[{"p":2,"q":1}],"a":{"x":2,"y":1}}""";

        assertThat(canonical.fingerprint(first)).isEqualTo(canonical.fingerprint(second));
    }

    @Test
    @DisplayName("a malformed amount is left alone rather than rewritten")
    void malformedAmountsAreNotCoerced() {
        // Validation's job, not fingerprinting's. Silently rewriting it here would hide the problem.
        assertThat(canonical.canonicalise("{\"quantity\":\"not-a-number\"}")).contains("not-a-number");
    }

    @Test
    @DisplayName("the fingerprint is 64 lowercase hex characters, as the column expects")
    void fingerprintShape() {
        assertThat(canonical.fingerprint("{\"symbol\":\"BTCUSDT\"}")).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("an absent or non-JSON body still fingerprints without throwing")
    void degradesGracefully() {
        assertThat(canonical.fingerprint(null)).matches("[0-9a-f]{64}");
        assertThat(canonical.fingerprint("")).matches("[0-9a-f]{64}");
        assertThat(canonical.fingerprint("not json at all")).matches("[0-9a-f]{64}");
        assertThat(canonical.fingerprint("not json at all")).isNotEqualTo(canonical.fingerprint("something else"));
    }
}
