package com.example.taskboard.service;

import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;

import java.util.EnumMap;
import java.util.Map;

public record TaskStats(
        long total,
        long overdue,
        long dueToday,
        Map<TaskStatus, Long> byStatus,
        Map<TaskPriority, Long> byPriority) {

    public TaskStats {
        byStatus = byStatus == null ? new EnumMap<>(TaskStatus.class) : byStatus;
        byPriority = byPriority == null ? new EnumMap<>(TaskPriority.class) : byPriority;
    }
}
