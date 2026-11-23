package com.example.taskboard.web;

import com.example.taskboard.model.TaskPriority;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Body for {@code POST /api/tasks} and {@code PUT /api/tasks/{id}}.
 *
 * <p>There is no {@code status} field on purpose. A new task always starts at TODO and an
 * existing one changes status only through {@code PATCH /{id}/status}.
 *
 * <p>Unknown names are rejected, so a misspelled field ("priorty") is an error rather than a
 * silent default. The read-only fields a {@code GET} returns are the exception: the common
 * "fetch, edit one field, put it back" pattern would otherwise fail on {@code id}, {@code status}
 * and the timestamps. Those are ignored, everything else must be spelled correctly.
 */
@JsonIgnoreProperties(value = {"id", "status", "createdAt", "updatedAt", "overdue", "daysOverdue"},
        ignoreUnknown = false)
public record TaskPayload(
        @NotBlank(message = "title must not be blank")
        @Size(max = 80, message = "title must be at most 80 characters")
        String title,

        TaskPriority priority,

        @Size(max = 40, message = "assignee must be at most 40 characters")
        String assignee,

        LocalDate dueDate,

        @Size(max = 5, message = "tags must contain at most 5 items")
        List<@NotBlank(message = "tag must not be blank")
             @Size(max = 20, message = "each tag must be at most 20 characters") String> tags) {
}
