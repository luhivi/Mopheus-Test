package com.example.taskboard.service;

/**
 * Raised when a task cannot move from its current status to the requested one.
 * A separate type so the handler can answer 409 instead of lumping it in with bad input.
 */
public class InvalidTaskTransitionException extends RuntimeException {

    private final long taskId;
    private final String from;
    private final String to;

    public InvalidTaskTransitionException(long taskId, String from, String to) {
        super("task " + taskId + " cannot move from " + from + " to " + to);
        this.taskId = taskId;
        this.from = from;
        this.to = to;
    }

    public long taskId() {
        return taskId;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }
}
