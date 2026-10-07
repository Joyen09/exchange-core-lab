package io.github.joyen09.exchangecore.api;

import io.github.joyen09.exchangecore.api.ApiExceptions.ValidationFailedException;
import io.github.joyen09.exchangecore.idempotency.IdempotencyExceptions.KeyRequiredException;
import io.github.joyen09.exchangecore.idempotency.IdempotencyExceptions.KeyReusedException;
import io.github.joyen09.exchangecore.idempotency.IdempotencyExceptions.RequestInProgressException;
import io.github.joyen09.exchangecore.order.IllegalStateTransitionException;
import io.github.joyen09.exchangecore.order.OrderExceptions.InvalidOrderException;
import io.github.joyen09.exchangecore.order.OrderExceptions.MarketOrderNotSupportedException;
import io.github.joyen09.exchangecore.order.OrderExceptions.OrderAlreadyTerminalException;
import io.github.joyen09.exchangecore.order.OrderExceptions.OrderNotFoundException;
import io.github.joyen09.exchangecore.order.OrderExceptions.UnknownSymbolException;
import io.github.joyen09.exchangecore.order.OrderPageCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as RFC 7807 {@code application/problem+json}, with a machine-readable {@code code}
 * alongside the human-readable title.
 *
 * <p>The status codes are deliberate and a few are worth noticing: a duplicate key with a
 * <em>different</em> body is 422 rather than 409, because retrying will not help; a duplicate key whose
 * first request is still running is 409, because retrying is exactly the right move.
 *
 * <h2>Why this extends {@link ResponseEntityExceptionHandler}</h2>
 *
 * Because {@code @ExceptionHandler(Exception.class)} below is otherwise too greedy. A
 * {@code @RestControllerAdvice} applies to the whole application, so the catch-all also caught Spring's
 * own exceptions — {@code NoResourceFoundException} for an unknown URL, the type mismatch for a path
 * variable that is not a UUID — and turned each of them into <b>500</b>. An unknown path answering
 * "internal error" is worse than merely untidy: it tells the caller the server is broken when the caller
 * is the one with the wrong URL, and it hides real failures in the same log line as typos.
 *
 * <p>The base class contributes handlers for the standard Spring MVC exceptions, each more specific than
 * {@code Exception}, so Spring prefers them and the catch-all is left to handle what is genuinely
 * unexpected. {@link #handleExceptionInternal} attaches a {@code code} to those responses so the
 * machine-readable contract holds for every error and not only for ours.
 *
 * <p>This was found by {@code HealthEndpointIT}, which asserts that unexposed actuator endpoints answer
 * 404 — a Phase 0 test, failing for the first time in Phase 2 because of a class added nowhere near it.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(KeyRequiredException.class)
    ProblemDetail keyRequired(KeyRequiredException e) {
        return problem(HttpStatus.BAD_REQUEST, "Idempotency-Key required", "IDEMPOTENCY_KEY_REQUIRED", e.getMessage());
    }

    @ExceptionHandler(KeyReusedException.class)
    ProblemDetail keyReused(KeyReusedException e) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key reused", "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(RequestInProgressException.class)
    ProblemDetail inProgress(RequestInProgressException e) {
        return problem(HttpStatus.CONFLICT, "Request in progress", "REQUEST_IN_PROGRESS", e.getMessage());
    }

    @ExceptionHandler(MarketOrderNotSupportedException.class)
    ProblemDetail marketOrder(MarketOrderNotSupportedException e) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Market orders not supported yet",
                "MARKET_ORDER_NOT_SUPPORTED_YET",
                e.getMessage());
    }

    @ExceptionHandler(UnknownSymbolException.class)
    ProblemDetail unknownSymbol(UnknownSymbolException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown symbol", "UNKNOWN_SYMBOL", e.getMessage());
    }

    @ExceptionHandler(InvalidOrderException.class)
    ProblemDetail invalidOrder(InvalidOrderException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "VALIDATION_FAILED", e.getMessage());
    }

    @ExceptionHandler(ValidationFailedException.class)
    ProblemDetail validationFailed(ValidationFailedException e) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "VALIDATION_FAILED", "one or more fields are invalid");
        problem.setProperty("errors", e.errors());
        return problem;
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", "ORDER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(OrderAlreadyTerminalException.class)
    ProblemDetail terminal(OrderAlreadyTerminalException e) {
        ProblemDetail problem =
                problem(HttpStatus.CONFLICT, "Order already terminal", "ORDER_ALREADY_TERMINAL", e.getMessage());
        problem.setProperty("status", e.status().name());
        return problem;
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail illegalTransition(IllegalStateTransitionException e) {
        ProblemDetail problem =
                problem(HttpStatus.CONFLICT, "Illegal state transition", "ILLEGAL_STATE_TRANSITION", e.getMessage());
        problem.setProperty("from", e.from().name());
        problem.setProperty("to", e.to().name());
        return problem;
    }

    @ExceptionHandler(OrderPageCursor.InvalidCursorException.class)
    ProblemDetail invalidCursor(OrderPageCursor.InvalidCursorException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid cursor", "INVALID_CURSOR", e.getMessage());
    }

    /**
     * Gives the base class's responses the same {@code code} property ours carry, derived from the
     * status, so a client can switch on one field for every error rather than two.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("code", codeFor(statusCode));
        }
        return response;
    }

    private static String codeFor(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status == null ? "HTTP_" + statusCode.value() : status.name();
    }

    /**
     * The catch-all, for what the base class does not already map. It logs the cause and returns nothing
     * about it: an unexpected failure is the one case where the client learning the detail is a risk and
     * the operator learning it is essential.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled failure serving a request", e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal error",
                "INTERNAL_ERROR",
                "the request could not be completed");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setProperty("code", code);
        return problem;
    }
}
