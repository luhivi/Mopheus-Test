package com.example.taskboard.web;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire shape of a task. Kept separate from the domain record so that adding a computed field
 * (overdue / daysOverdue) never forces a change to stored state.
 */
public record TaskResponse(
        long id,
        String title,
        TaskStatus status,
        TaskPriority priority,
        String assignee,
        LocalDate dueDate,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        boolean overdue,
        long daysOverdue) {

    public static TaskResponse from(Task task, LocalDate today) {
        long daysOverdue = 0;
        boolean overdue = task.isOverdue(today);
        if (overdue) {
            daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(task.dueDate(), today);
        }
        return new TaskResponse(task.id(), task.title(), task.status(), task.priority(), task.assignee(),
                task.dueDate(), task.tags(), task.createdAt(), task.updatedAt(), overdue, daysOverdue);
    }
}
