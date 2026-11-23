package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Query options for {@link TaskService#findAll(TaskQuery)}.
 *
 * <p>{@code q} matches the title or any tag, case-insensitively. {@code overdue} is deliberately
 * a tri-state Boolean: {@code null} means "do not filter", which is different from {@code false}
 * ("only tasks that are not overdue").
 */
public record TaskQuery(
        List<TaskStatus> statuses,
        List<TaskPriority> priorities,
        String q,
        String assignee,
        Boolean overdue,
        SortKey sort,
        boolean descending,
        LocalDate today) {

    public enum SortKey {
        CREATED_AT,
        DUE_DATE,
        PRIORITY
    }

    public TaskQuery {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        priorities = priorities == null ? List.of() : List.copyOf(priorities);
        sort = sort == null ? SortKey.CREATED_AT : sort;
        today = today == null ? LocalDate.now() : today;
    }

    public List<Task> apply(List<Task> source) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        String owner = assignee == null ? "" : assignee.trim().toLowerCase();

        List<Task> filtered = new ArrayList<>();
        for (Task task : source) {
            if (!statuses.isEmpty() && !statuses.contains(task.status())) {
                continue;
            }
            if (!priorities.isEmpty() && !priorities.contains(task.priority())) {
                continue;
            }
            if (!needle.isEmpty() && !matchesText(task, needle)) {
                continue;
            }
            if (!owner.isEmpty() && (task.assignee() == null || !task.assignee().toLowerCase().contains(owner))) {
                continue;
            }
            if (overdue != null && task.isOverdue(today) != overdue) {
                continue;
            }
            filtered.add(task);
        }

        filtered.sort(comparator());
        return List.copyOf(filtered);
    }

    private static boolean matchesText(Task task, String needle) {
        if (task.title().toLowerCase().contains(needle)) {
            return true;
        }
        return task.tags().stream().anyMatch(tag -> tag.toLowerCase().contains(needle));
    }

    private Comparator<Task> comparator() {
        Comparator<Task> base = switch (sort) {
            case DUE_DATE -> Comparator.comparing(Task::dueDate, Comparator.nullsLast(Comparator.naturalOrder()));
            case PRIORITY -> Comparator.comparingInt(task -> task.priority().ordinal());
            case CREATED_AT -> Comparator.comparing(Task::createdAt);
        };
        // The tie-breaker is always the id so that two tasks created in the same millisecond keep a
        // stable order; without it a page refresh could shuffle rows and look like a bug.
        Comparator<Task> withTieBreak = base.thenComparingLong(Task::id);
        return descending ? withTieBreak.reversed() : withTieBreak;
    }
}
