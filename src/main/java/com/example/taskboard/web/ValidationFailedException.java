package com.example.taskboard.web;

/**
 * Raised when a request is well-formed JSON but its values cannot be accepted as a whole.
 *
 * <p>Distinct from {@link MalformedQueryException}, which is about the query string, and from
 * bean-validation failures: batch and import endpoints take loosely typed bodies (an id array, a
 * list of rows) where the error is in the structure itself.
 */
public class ValidationFailedException extends RuntimeException {

    public ValidationFailedException(String message) {
        super(message);
    }
}