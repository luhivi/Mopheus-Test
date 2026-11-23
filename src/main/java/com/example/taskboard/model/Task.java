package com.example.taskboard.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record Task(
        long id,
        String title,
        TaskStatus status,
        TaskPriority priority,
        String assignee,
        LocalDate dueDate,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt) {

    public Task {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public boolean isOverdue(LocalDate today) {
        return dueDate != null && dueDate.isBefore(today) && status != TaskStatus.DONE;
    }

    public boolean isDueToday(LocalDate today) {
        return dueDate != null && dueDate.isEqual(today) && status != TaskStatus.DONE;
    }
}
