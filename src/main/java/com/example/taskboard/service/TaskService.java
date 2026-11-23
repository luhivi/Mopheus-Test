package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TaskService {

    private final ConcurrentMap<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;

    public TaskService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Everything the service needs to build or replace a task. There is deliberately no status
     * field: status is set to TODO on create and changed only through {@link #updateStatus}.
     */
    public record Draft(
            String title,
            TaskPriority priority,
            String assignee,
            LocalDate dueDate,
            List<String> tags) {
    }

    public List<Task> findAll(TaskQuery query) {
        TaskQuery effective = query == null ? new TaskQuery(null, null, null, null, null, null, true, null) : query;
        return effective.apply(new ArrayList<>(tasks.values()));
    }

    public Task findById(long id) {
        Task task = tasks.get(id);
        if (task == null) {
            throw new TaskNotFoundException(id);
        }
        return task;
    }

    public Task create(Draft draft) {
        Instant now = Instant.now(clock);
        long id = sequence.incrementAndGet();
        Task task = new Task(
                id,
                normalizeTitle(draft.title()),
                // New tasks always start at TODO. Accepting an initial status here would let any
                // client skip the state machine entirely on the very first request.
                TaskStatus.TODO,
                draft.priority() == null ? TaskPriority.NORMAL : draft.priority(),
                normalizeAssignee(draft.assignee()),
                draft.dueDate(),
                normalizeTags(draft.tags()),
                now,
                now);
        tasks.put(id, task);
        return task;
    }

    /**
     * Full replacement of the editable fields. Status is not editable here: it is owned by
     * {@link #updateStatus} so a status change can never bypass the transition table.
     *
     * <p>Semantics are strict PUT: a field that is absent from the request is cleared, not kept.
     * The earlier version kept {@code priority} when it was missing but cleared the rest, which
     * meant "edit only the title" silently erased the assignee and due date — the exact failure a
     * demo cannot afford. Callers that want partial edits should send the whole object.
     */
    public Task update(long id, Draft draft) {
        Instant now = Instant.now(clock);
        Task updated = tasks.computeIfPresent(id, (key, current) -> new Task(
                current.id(),
                normalizeTitle(draft.title()),
                current.status(),
                draft.priority() == null ? TaskPriority.NORMAL : draft.priority(),
                normalizeAssignee(draft.assignee()),
                draft.dueDate(),
                normalizeTags(draft.tags()),
                current.createdAt(),
                now));
        if (updated == null) {
            throw new TaskNotFoundException(id);
        }
        return updated;
    }

    public Task updateStatus(long id, TaskStatus target) {
        Instant now = Instant.now(clock);
        // Throwing inside compute is safe: ConcurrentHashMap documents that a remapping function
        // which throws leaves the mapping unchanged, so there is no half-updated state to guard
        // against and no need to smuggle the previous value out through a holder.
        Task updated = tasks.compute(id, (key, current) -> {
            if (current == null) {
                return null;
            }
            if (!current.status().canMoveTo(target)) {
                throw new InvalidTaskTransitionException(id, current.status().name(), String.valueOf(target));
            }
            if (current.status() == target) {
                // Idempotent no-op: keep the entry as is, including updatedAt.
                return current;
            }
            return new Task(current.id(), current.title(), target, current.priority(), current.assignee(),
                    current.dueDate(), current.tags(), current.createdAt(), now);
        });
        if (updated == null) {
            throw new TaskNotFoundException(id);
        }
        return updated;
    }

    public void delete(long id) {
        if (tasks.remove(id) == null) {
            throw new TaskNotFoundException(id);
        }
    }

    public TaskStats stats(LocalDate today) {
        Map<TaskStatus, Long> byStatus = new EnumMap<>(TaskStatus.class);
        Map<TaskPriority, Long> byPriority = new EnumMap<>(TaskPriority.class);
        for (TaskStatus status : TaskStatus.values()) {
            byStatus.put(status, 0L);
        }
        for (TaskPriority priority : TaskPriority.values()) {
            byPriority.put(priority, 0L);
        }

        long overdue = 0;
        long dueToday = 0;
        for (Task task : tasks.values()) {
            byStatus.merge(task.status(), 1L, Long::sum);
            byPriority.merge(task.priority(), 1L, Long::sum);
            if (task.isOverdue(today)) {
                overdue++;
            }
            if (task.isDueToday(today)) {
                dueToday++;
            }
        }
        return new TaskStats(tasks.size(), overdue, dueToday, byStatus, byPriority);
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.trim();
    }

    private static String normalizeAssignee(String assignee) {
        if (assignee == null) {
            return null;
        }
        String trimmed = assignee.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return List.copyOf(unique);
    }
}
