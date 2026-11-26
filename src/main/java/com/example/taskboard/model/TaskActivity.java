package com.example.taskboard.model;

import java.time.Instant;

/**
 * One audit row for a task.
 *
 * <p>{@code taskId} is a plain value, not a foreign key: deleting a task must keep its history.
 * The deleted row is the only evidence left that the task ever existed, so a cascade would erase
 * exactly what an audit trail is for.
 */
public record TaskActivity(
        long id,
        long taskId,
        TaskActivityType type,
        String detail,
        Instant createdAt) {
}