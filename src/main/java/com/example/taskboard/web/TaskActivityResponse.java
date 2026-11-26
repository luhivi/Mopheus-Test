package com.example.taskboard.web;

import com.example.taskboard.model.TaskActivityType;

import java.time.Instant;

/** Wire shape of one history entry. */
public record TaskActivityResponse(
        long id,
        long taskId,
        TaskActivityType type,
        String detail,
        Instant createdAt) {
}