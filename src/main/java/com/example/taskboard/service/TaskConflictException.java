package com.example.taskboard.service;

/**
 * Raised when two writes read the same row version and the later one loses the optimistic lock.
 *
 * <p>Deliberately separate from {@link InvalidTaskTransitionException}: a lost race is retryable
 * and says nothing about the state machine, so it answers 409 with its own code. Named
 * {@code TaskConflictException} rather than {@code ConcurrentModificationException} so it does
 * not shadow {@code java.util.ConcurrentModificationException} at call sites.
 */
public class TaskConflictException extends RuntimeException {

    private final long taskId;

    public TaskConflictException(long taskId) {
        super("task " + taskId + " was modified by another request; reload and retry");
        this.taskId = taskId;
    }

    public long taskId() {
        return taskId;
    }
}