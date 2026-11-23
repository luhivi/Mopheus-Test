package com.example.taskboard.web;

import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Body for {@code POST /api/tasks} and {@code PUT /api/tasks/{id}}.
 *
 * <p>{@code status} is accepted only on create; on update the status is owned by the dedicated
 * PATCH endpoint so a full edit can never smuggle a transition past the state machine.
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
             @Size(max = 20, message = "each tag must be at most 20 characters") String> tags,

        TaskStatus status) {
}
