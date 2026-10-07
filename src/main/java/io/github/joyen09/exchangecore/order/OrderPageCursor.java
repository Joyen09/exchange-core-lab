package io.github.joyen09.exchangecore.order;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * An opaque position in the order list: the {@code (created_at, id)} of the last row returned.
 *
 * <p>Base64 of a fixed two-field form, so clients cannot build one by hand and the server is free to
 * change the encoding. It carries no information a client could not already see in the row it came
 * from.
 */
public record OrderPageCursor(Instant createdAt, UUID id) {

    public String encode() {
        String raw = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static OrderPageCursor decode(String encoded) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException(encoded);
        }
        int separator = raw.indexOf(':');
        if (separator < 0) {
            throw new InvalidCursorException(encoded);
        }
        try {
            return new OrderPageCursor(
                    Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException(encoded);
        }
    }

    /** A cursor that did not come from this service, or was truncated in transit. */
    public static class InvalidCursorException extends RuntimeException {
        public InvalidCursorException(String cursor) {
            super("cursor is not a valid page position: " + cursor);
        }
    }
}
