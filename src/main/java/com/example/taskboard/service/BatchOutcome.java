package com.example.taskboard.service;

import com.example.taskboard.model.Task;

/**
 * One row of a batch request's response.
 *
 * <p>{@code task} is present only for successful status changes; a failed row carries the error
 * code and message that the caller needs to show instead. Both are also representable as
 * {@code null}, which is why the web layer decides which fields to serialize rather than relying
 * on a non-null convention.
 */
public record BatchOutcome(long id, boolean ok, Task task, String code, String message) {

    public static BatchOutcome success(long id, Task task) {
        return new BatchOutcome(id, true, task, null, null);
    }

    public static BatchOutcome failure(long id, String code, String message) {
        return new BatchOutcome(id, false, null, code, message);
    }
}