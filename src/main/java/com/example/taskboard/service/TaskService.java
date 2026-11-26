package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskActivityType;
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
import java.util.Objects;

/**
 * All task rules live here: normalisation, the transition table, strict PUT and the activity
 * suppression rules. Storage is a port ({@link TaskStore}) so these rules can be tested without a
 * database, and so the JPA layer never decides what a change means.
 */
@Service
public class TaskService {

    private final TaskStore store;
    private final Clock clock;

    public TaskService(TaskStore store, Clock clock) {
        this.store = store;
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
        return effective.apply(new ArrayList<>(store.findAll()));
    }

    public Task findById(long id) {
        return store.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    public Task create(Draft draft) {
        Instant now = Instant.now(clock);
        // The id is a placeholder: storage assigns it so IDENTITY and the id sequence agree.
        Task task = new Task(
                0L,
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
        return store.insert(task, TaskActivityType.CREATED, "创建任务");
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
        return store.mutate(id, current -> {
            Task next = new Task(
                    current.id(),
                    normalizeTitle(draft.title()),
                    current.status(),
                    draft.priority() == null ? TaskPriority.NORMAL : draft.priority(),
                    normalizeAssignee(draft.assignee()),
                    draft.dueDate(),
                    normalizeTags(draft.tags()),
                    current.createdAt(),
                    now);
            List<String> changed = changedFields(current, next);
            if (changed.isEmpty()) {
                // Strict PUT with the same content is not an edit. Writing it would bump
                // updatedAt and log an UPDATED row that lists nothing, both of which are lies.
                return TaskStore.Plan.unchanged(current);
            }
            return new TaskStore.Plan(next, TaskActivityType.UPDATED, String.join("、", changed));
        });
    }

    public Task updateStatus(long id, TaskStatus target) {
        Instant now = Instant.now(clock);
        return store.mutate(id, current -> {
            if (!current.status().canMoveTo(target)) {
                throw new InvalidTaskTransitionException(id, current.status().name(), String.valueOf(target));
            }
            if (current.status() == target) {
                // Idempotent no-op: keep the entry as is, including updatedAt, and log nothing.
                // A STATUS_CHANGED row for "TODO → TODO" would contradict the detail format.
                return TaskStore.Plan.unchanged(current);
            }
            Task next = new Task(current.id(), current.title(), target, current.priority(), current.assignee(),
                    current.dueDate(), current.tags(), current.createdAt(), now);
            return new TaskStore.Plan(next, TaskActivityType.STATUS_CHANGED,
                    current.status().name() + " → " + target.name());
        });
    }

    public void delete(long id) {
        // The DELETED row is written by storage in the same transaction as the row removal, so a
        // deleted task is never left without the evidence that it existed.
        store.delete(id, TaskActivityType.DELETED, "删除任务");
    }

    public List<TaskActivity> activities(long id, int limit) {
        List<TaskActivity> found = store.activities(id, limit);
        if (found.isEmpty() && store.findById(id).isEmpty()) {
            // No task and no history: the resource never existed as far as the caller can tell.
            // History without a task is the *deleted* case and must still return 200.
            throw new TaskNotFoundException(id);
        }
        return found;
    }

    /**
     * Applies a status change to each id, independently.
     *
     * <p>Not transactional as a whole on purpose: every row is its own commit, so one illegal
     * transition cannot roll back the rows that succeeded. A single large transaction would also
     * hold the whole batch's locks and turn one bad id into an all-or-nothing request, which is
     * the opposite of what a "select several cards, move them" action should do.
     *
     * <p>Ids are de-duplicated in first-seen order before execution, and the response follows that
     * same order so the client can map results back to what it sent.
     */
    public List<BatchOutcome> batchStatus(List<Long> ids, TaskStatus target) {
        List<BatchOutcome> outcomes = new ArrayList<>();
        for (long id : deduplicate(ids)) {
            try {
                Task updated = updateStatus(id, target);
                outcomes.add(BatchOutcome.success(id, updated));
            } catch (TaskNotFoundException ex) {
                outcomes.add(BatchOutcome.failure(id, "TASK_NOT_FOUND", ex.getMessage()));
            } catch (InvalidTaskTransitionException ex) {
                outcomes.add(BatchOutcome.failure(id, "INVALID_TRANSITION",
                        "task cannot move from " + ex.from() + " to " + ex.to()));
            } catch (TaskConflictException ex) {
                outcomes.add(BatchOutcome.failure(id, "CONCURRENT_MODIFICATION", ex.getMessage()));
            }
        }
        return outcomes;
    }

    public List<BatchOutcome> batchDelete(List<Long> ids) {
        List<BatchOutcome> outcomes = new ArrayList<>();
        for (long id : deduplicate(ids)) {
            try {
                delete(id);
                outcomes.add(BatchOutcome.success(id, null));
            } catch (TaskNotFoundException ex) {
                outcomes.add(BatchOutcome.failure(id, "TASK_NOT_FOUND", ex.getMessage()));
            } catch (TaskConflictException ex) {
                outcomes.add(BatchOutcome.failure(id, "CONCURRENT_MODIFICATION", ex.getMessage()));
            }
        }
        return outcomes;
    }

    /** De-duplicates while keeping first-seen order; that order is the response order. */
    private static List<Long> deduplicate(List<Long> ids) {
        java.util.LinkedHashSet<Long> unique = new java.util.LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                unique.add(id);
            }
        }
        return List.copyOf(unique);
    }

    public TaskStats stats(LocalDate today) {
        List<Task> all = store.findAll();
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
        for (Task task : all) {
            byStatus.merge(task.status(), 1L, Long::sum);
            byPriority.merge(task.priority(), 1L, Long::sum);
            if (task.isOverdue(today)) {
                overdue++;
            }
            if (task.isDueToday(today)) {
                dueToday++;
            }
        }
        return new TaskStats(all.size(), overdue, dueToday, byStatus, byPriority);
    }

    /**
     * Field names are Chinese labels because the detail string is rendered verbatim in the UI.
     * The list is built in a fixed order rather than from a map so the same edit always produces
     * the same string, which makes the activity feed diffable.
     */
    private static List<String> changedFields(Task before, Task after) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(before.title(), after.title())) {
            changed.add("标题");
        }
        if (before.priority() != after.priority()) {
            changed.add("优先级");
        }
        if (!Objects.equals(before.assignee(), after.assignee())) {
            changed.add("负责人");
        }
        if (!Objects.equals(before.dueDate(), after.dueDate())) {
            changed.add("截止日期");
        }
        if (!before.tags().equals(after.tags())) {
            changed.add("标签");
        }
        return changed;
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