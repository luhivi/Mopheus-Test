package com.example.taskboard.web;

/**
 * Raised by request-level checks that bean validation cannot express, such as "ids must be an
 * array of 1..200 entries" or "mode must be merge or replace". Mapped to 400
 * {@code VALIDATION_FAILED} so the client sees the same code it already handles for field errors.
 */
public class ValidationFailedException extends RuntimeException {

    public ValidationFailedException(String message) {
        super(message);
    }
}