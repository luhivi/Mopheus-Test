package com.example.taskboard.web;

import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.service.TaskStats;

import java.util.LinkedHashMap;
import java.util.Map;

public record TaskStatsResponse(
        long total,
        long overdue,
        long dueToday,
        Map<String, Long> byStatus,
        Map<String, Long> byPriority) {

    public static TaskStatsResponse from(TaskStats stats) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            byStatus.put(status.name(), stats.byStatus().getOrDefault(status, 0L));
        }
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (TaskPriority priority : TaskPriority.values()) {
            byPriority.put(priority.name(), stats.byPriority().getOrDefault(priority, 0L));
        }
        return new TaskStatsResponse(stats.total(), stats.overdue(), stats.dueToday(), byStatus, byPriority);
    }
}
