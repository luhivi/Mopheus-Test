package com.example.taskboard.web;

import com.example.taskboard.model.Task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Body of {@code GET /api/tasks/export}. */
public record ExportResponse(Instant exportedAt, long count, List<TaskResponse> tasks) {

    public static ExportResponse from(Instant exportedAt, List<Task> tasks, LocalDate today) {
        List<TaskResponse> rows = tasks.stream().map(task -> TaskResponse.from(task, today)).toList();
        return new ExportResponse(exportedAt, rows.size(), rows);
    }
}