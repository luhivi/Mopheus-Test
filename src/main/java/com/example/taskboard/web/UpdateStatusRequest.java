package com.example.taskboard.web;

import com.example.taskboard.model.TaskStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code PATCH /api/tasks/{id}/status}.
 *
 * <p>Only {@code status} is applied. The read-only and editable fields a {@code GET} returns are
 * ignored rather than rejected, so "read a task, change its status, send it back" works — the same
 * courtesy {@link TaskPayload} extends on PUT. Everything else must be spelled correctly.
 */
@JsonIgnoreProperties(value = {"id", "title", "priority", "assignee", "dueDate", "tags",
        "createdAt", "updatedAt", "overdue", "daysOverdue"},
        ignoreUnknown = false)
public record UpdateStatusRequest(
        @NotNull(message = "status must not be null") TaskStatus status) {
}
