package com.example.taskboard.web;

import com.example.taskboard.service.InvalidTaskTransitionException;
import com.example.taskboard.service.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Serializes every error as {@code {code, message, timestamp}} without leaking stack traces.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} keeps Spring's own status codes (404 for a
 * missing resource, 405 for a wrong method, 415 for an unsupported media type) instead of letting
 * them fall through to the catch-all and become 500s.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorResponse(String code, String message, Instant timestamp) {
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Object> handleTaskNotFound(TaskNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidTaskTransitionException.class)
    public ResponseEntity<Object> handleInvalidTransition(InvalidTaskTransitionException ex) {
        // 409 rather than 400: the request itself is well formed and the target status exists.
        // The conflict is with the resource's current state, which is exactly what 409 means.
        return build(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                "task cannot move from " + ex.from() + " to " + ex.to());
    }

    @ExceptionHandler(MalformedQueryException.class)
    public ResponseEntity<Object> handleMalformedQuery(MalformedQueryException ex) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_QUERY", ex.getMessage());
    }

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<Object> handleValidationFailed(ValidationFailedException ex) {
        // Request-level failure for batch and import: the body parsed as JSON, but its shape or
        // values cannot be accepted for the request as a whole. Row-level failures are not errors
        // and never reach this handler.
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage());
    }

    @ExceptionHandler(ReplaceNotConfirmedException.class)
    public ResponseEntity<Object> handleReplaceNotConfirmed(ReplaceNotConfirmedException ex) {
        return build(HttpStatus.BAD_REQUEST, "REPLACE_NOT_CONFIRMED", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex) {
        // Two requests changed the same task; the loser's write was not applied. 409 tells the
        // caller to re-read and retry rather than leaving it to guess whether it won.
        log.debug("Optimistic lock conflict: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "the task was modified by another request");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // Two different mistakes arrive here and they must not share a code: `?overdue=maybe` is a
        // bad query string, while `/api/tasks/abc` is a path segment that cannot be an id. Calling
        // the latter MALFORMED_QUERY tells the caller to look in the wrong place.
        boolean pathVariable = ex.getParameter().hasParameterAnnotation(PathVariable.class);
        if (pathVariable) {
            return build(HttpStatus.BAD_REQUEST, "MALFORMED_PATH",
                    "path segment '" + ex.getName() + "' is not a valid value");
        }
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_QUERY",
                "query parameter '" + ex.getName() + "' has an unsupported value");
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // The negotiated type cannot be honoured. This endpoint only speaks JSON, so the
        // body is pinned to application/json instead of re-entering content negotiation.
        log.debug("No acceptable representation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("NOT_ACCEPTABLE", "this endpoint produces application/json only", Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while serving API request", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal server error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", details);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Request failed with status {}", statusCode.value(), ex);
            return build(headers, HttpStatus.valueOf(statusCode.value()), "INTERNAL_ERROR", "internal server error");
        }

        // 4xx from Spring MVC itself: missing body, unknown value, bad method, missing resource.
        // The message is a fixed whitelist string, so enum names or class names never leak.
        log.debug("Request rejected with status {}: {}", statusCode.value(), ex.getMessage());

        int status = statusCode.value();
        if (status == HttpStatus.NOT_FOUND.value()) {
            return build(headers, HttpStatus.NOT_FOUND, "NOT_FOUND", "resource not found");
        }
        if (status == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return build(headers, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "HTTP method is not supported here");
        }
        if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return build(headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Content-Type must be application/json");
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return build(headers, HttpStatus.BAD_REQUEST, "MALFORMED_BODY",
                    "request body is malformed or has an unknown value");
        }
        return build(headers, HttpStatus.valueOf(status), "BAD_REQUEST", "request could not be processed");
    }

    private ResponseEntity<Object> build(HttpStatus status, String code, String message) {
        return build(new HttpHeaders(), status, code, message);
    }

    /**
     * Carries over the headers Spring already decided on. A 405 must echo {@code Allow}
     * (RFC 9110 §15.5.6); dropping them makes the response non-conformant.
     *
     * <p>The media type is pinned to JSON because this handler serves both the framework
     * exceptions and the application's own: without it, a client sending
     * {@code Accept: text/html} cannot be satisfied, the handler itself fails, and Spring
     * degrades the original 404 into a 500 Whitelabel page.
     */
    private ResponseEntity<Object> build(HttpHeaders headers, HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(code, message, Instant.now()));
    }
}
