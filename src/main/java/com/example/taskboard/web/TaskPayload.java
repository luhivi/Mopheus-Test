package com.example.taskboard.web;

import com.example.taskboard.model.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Body for {@code POST /api/tasks} and {@code PUT /api/tasks/{id}}.
 *
 * <p>There is no {@code status} field on purpose. A new task always starts at TODO and an
 * existing one changes status only through {@code PATCH /{id}/status}. Accepting a status here
 * would let a single request skip the transition table, which is the whole point of having one.
 */
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
