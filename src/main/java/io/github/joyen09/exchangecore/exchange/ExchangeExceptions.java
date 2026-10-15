package io.github.joyen09.exchangecore.exchange;

import java.time.Duration;

/**
 * Failures that are not answers.
 *
 * <p>The division matters to the submitter and is made here rather than left to it: a venue that
 * <em>refused</em> the order returns a {@link PlaceResult.Rejected}, which is information. A venue that
 * could not be reached throws, because nothing is known — and ADR-0010 is emphatic that "we do not
 * know" must never be written down as {@code REJECTED}, which would release the locked funds of an
 * order that may be live at the exchange.
 *
 * <p>So: results are deterministic outcomes, exceptions are the absence of one. Nothing here carries an
 * HTTP status, because the caller's decision is "retry or not", not "was it a 503".
 */
public final class ExchangeExceptions {

    private ExchangeExceptions() {}

    /** Base type, so a caller can catch the category without enumerating it. */
    public abstract static class ExchangeUnavailableException extends RuntimeException {
        protected ExchangeUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        /** Whether another attempt is worth making at all. */
        public abstract boolean retryable();
    }

    /** A transport failure or a 5xx: the request may or may not have been seen. Safe to retry, because
     * every call carries the same client order id and the venue de-duplicates on it. */
    public static final class VenueUnreachableException extends ExchangeUnavailableException {
        public VenueUnreachableException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** Rate limited (HTTP 429). Retryable, but not before {@link #retryAfter()}. */
    public static final class VenueRateLimitedException extends ExchangeUnavailableException {
        private final Duration retryAfter;

        public VenueRateLimitedException(String message, Duration retryAfter) {
            super(message, null);
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /**
     * The IP is banned (HTTP 418). <b>Not</b> retryable, and the distinction is the whole reason this is
     * a separate type: continuing to call during a ban extends it. The correct response is to stop and
     * raise an alarm, which a caller cannot do if this arrives as "some other 4xx".
     */
    public static final class VenueBannedException extends ExchangeUnavailableException {
        private final Duration bannedFor;

        public VenueBannedException(String message, Duration bannedFor) {
            super(message, null);
            this.bannedFor = bannedFor;
        }

        public Duration bannedFor() {
            return bannedFor;
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }
}
