package io.github.joyen09.exchangecore.api;

import java.util.List;

/** API-level failures that carry their own problem code. */
public final class ApiExceptions {

    private ApiExceptions() {}

    /** A field-level error, so the client is told which field and why rather than just "invalid". */
    public record FieldError(String field, String detail) {}

    public static class ValidationFailedException extends RuntimeException {
        private final List<FieldError> errors;

        public ValidationFailedException(List<FieldError> errors) {
            super("the request body is not valid: " + errors);
            this.errors = List.copyOf(errors);
        }

        public List<FieldError> errors() {
            return errors;
        }
    }
}
