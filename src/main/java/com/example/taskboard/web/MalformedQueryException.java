package com.example.taskboard.web;

/**
 * Raised when a query string names a filter the API does not define.
 * A dedicated type keeps this a 400 instead of letting it reach the catch-all as a 500.
 *
 * <p>The offending value is deliberately not part of the message that reaches the client:
 * a search term can carry a pasted token or a phone number, and an error body ends up in logs,
 * browser history and screenshots. The parameter name is a fixed whitelist entry and is safe.
 */
public class MalformedQueryException extends RuntimeException {

    public MalformedQueryException(String parameterName, String value) {
        // `value` is accepted so callers do not have to remember not to use it; it is never
        // echoed. Keeping it in the signature makes that intent obvious at the call site.
        super("query parameter '" + parameterName + "' has an unsupported value");
    }
}
