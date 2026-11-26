package com.example.taskboard.web;

/**
 * Raised when {@code mode=replace} arrives without the explicit confirmation token.
 *
 * <p>Replace deletes every task and every history row. The front end already double-confirms, but
 * a confirmation that lives only in the browser is not a guard: any script, curl call or replayed
 * request bypasses it. Requiring the token in the body makes the destructive intent something the
 * caller has to state, not something the server infers from {@code mode}.
 */
public class ReplaceNotConfirmedException extends RuntimeException {

    public ReplaceNotConfirmedException() {
        super("replace mode requires confirm=replace");
    }
}