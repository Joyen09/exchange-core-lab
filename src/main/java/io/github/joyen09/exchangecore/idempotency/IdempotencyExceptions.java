package io.github.joyen09.exchangecore.idempotency;

/** Failures the API maps to 400, 409 and 422 respectively. */
public final class IdempotencyExceptions {

    private IdempotencyExceptions() {}

    /** The header is mandatory: without it there is no way to make a retry safe. */
    public static class KeyRequiredException extends RuntimeException {
        public KeyRequiredException() {
            super("the Idempotency-Key header is required on this endpoint");
        }
    }

    /** Same key, different request. Honouring it would replay a response for a different question. */
    public static class KeyReusedException extends RuntimeException {
        public KeyReusedException(String key) {
            super("Idempotency-Key %s was already used for a different request body".formatted(key));
        }
    }

    /** Same key, and the first request has not finished yet. The client should retry. */
    public static class RequestInProgressException extends RuntimeException {
        public RequestInProgressException(String key) {
            super("a request with Idempotency-Key %s is still being processed".formatted(key));
        }
    }
}
