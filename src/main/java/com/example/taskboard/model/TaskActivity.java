package com.example.taskboard.model;

import java.time.Instant;

/** One entry in a task's history, ready for the wire. */
public record TaskActivity(
        long id,
        long taskId,
        TaskActivityType type,
        String detail,
        Instant createdAt) {
}