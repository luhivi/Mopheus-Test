package com.example.taskboard.web;

/**
 * Raised when a query string names a filter the API does not define.
 * A dedicated type keeps this a 400 instead of letting it reach the catch-all as a 500.
 */
public class MalformedQueryException extends RuntimeException {

    public MalformedQueryException(String parameterName, String value) {
        super("query parameter '" + parameterName + "' has unsupported value '" + value + "'");
    }
}
