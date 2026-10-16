package io.github.joyen09.exchangecore.fakevenue;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over the query string, verified for real.
 *
 * <p>This is the one place where the fake venue's fidelity risk is acceptable in both directions. If
 * the signing code and this verifier are wrong in the same way, the opt-in testnet test catches it
 * immediately — a wrong signature is the one error the real venue reports unambiguously, with nothing
 * to misread. So implementing it here costs little and buys the thing most likely to be subtly wrong
 * being exercised on every offline run instead of only when somebody has credentials.
 *
 * <p>The signature covers the query string <em>as sent</em>, not a re-encoding of the parsed
 * parameters. Re-encoding would make the verifier agree with any client that happens to order or
 * escape its parameters the way this code does, which is exactly the class of agreement-by-coincidence
 * the fixtures exist to prevent.
 */
final class VenueSignature {

    private VenueSignature() {}

    static boolean isValid(HttpExchange exchange, String expectedApiKey, String apiSecret) {
        String presentedKey = exchange.getRequestHeaders().getFirst("X-MBX-APIKEY");
        if (presentedKey == null || !presentedKey.equals(expectedApiKey)) {
            return false;
        }

        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || !query.contains("signature=")) {
            return false;
        }

        int at = query.lastIndexOf("&signature=");
        if (at < 0) {
            // signature= with nothing before it: the payload would be empty, which no real request is.
            return false;
        }
        String payload = query.substring(0, at);
        String presented = query.substring(at + "&signature=".length());

        // Constant-time, because this verifier is read as an example of how the real thing works and a
        // short-circuiting comparison here would teach the wrong habit at a glance.
        return MessageDigest.isEqual(
                sign(payload, apiSecret).getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Exposed so a test can sign a request the way a client would, without duplicating the algorithm. */
    static String sign(String payload, String apiSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is required by the JDK and must be present", e);
        }
    }
}
