package com.example.taskboard.support;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskActivityType;
import com.example.taskboard.service.TaskNotFoundException;
import com.example.taskboard.service.TaskStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link TaskStore} for service tests.
 *
 * <p>Mirrors the JPA store's semantics — id assignment, insert/mutate/delete activity logging, the
 * "unchanged means write nothing" rule and the newest-first activity ordering — so a service test
 * exercises the real rules without paying for a database. The JPA implementation itself is covered
 * separately by {@code TaskPersistenceTest}, which is what stops the two from drifting.
 */
public class InMemoryTaskStore implements TaskStore {

    private final Map<Long, Task> tasks = new LinkedHashMap<>();
    private final List<TaskActivity> activities = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong activitySequence = new AtomicLong();
    private final Clock clock;

    public InMemoryTaskStore() {
        this(Clock.systemUTC());
    }

    public InMemoryTaskStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<Task> findAll() {
        return List.copyOf(tasks.values());
    }

    @Override
    public Optional<Task> findById(long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public long count() {
        return tasks.size();
    }

    @Override
    public Task insert(Task task, TaskActivityType activityType, String activityDetail) {
        Task saved = withId(task, sequence.incrementAndGet());
        tasks.put(saved.id(), saved);
        appendActivity(saved.id(), activityType, activityDetail, saved.createdAt());
        return saved;
    }

    @Override
    public Task mutate(long id, Planner planner) {
        Task current = tasks.get(id);
        if (current == null) {
            throw new TaskNotFoundException(id);
        }
        Plan plan = planner.plan(current);
        if (plan.next().equals(current)) {
            return current;
        }
        tasks.put(id, plan.next());
        appendActivity(id, plan.activityType(), plan.activityDetail(), plan.next().updatedAt());
        return plan.next();
    }

    @Override
    public void delete(long id, TaskActivityType activityType, String activityDetail) {
        if (tasks.remove(id) == null) {
            throw new TaskNotFoundException(id);
        }
        appendActivity(id, activityType, activityDetail, Instant.now(clock));
    }

    @Override
    public List<TaskActivity> activities(long taskId, int limit) {
        return activities.stream()
                .filter(activity -> activity.taskId() == taskId)
                .sorted(Comparator.comparing(TaskActivity::createdAt)
                        .thenComparingLong(TaskActivity::id)
                        .reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void clearAll() {
        tasks.clear();
        activities.clear();
    }

    @Override
    public List<Task> insertAll(List<Task> newTasks) {
        List<Task> saved = new ArrayList<>();
        for (Task task : newTasks) {
            saved.add(withId(task, sequence.incrementAndGet()));
        }
        for (Task task : saved) {
            tasks.put(task.id(), task);
        }
        return List.copyOf(saved);
    }

    @Override
    public List<Task> replaceAll(List<Task> newTasks) {
        clearAll();
        return insertAll(newTasks);
    }

    private void appendActivity(long taskId, TaskActivityType type, String detail, Instant createdAt) {
        if (type == null) {
            return;
        }
        activities.add(new TaskActivity(activitySequence.incrementAndGet(), taskId, type, detail, createdAt));
    }

    private static Task withId(Task task, long id) {
        return new Task(id, task.title(), task.status(), task.priority(), task.assignee(), task.dueDate(),
                task.tags(), task.createdAt(), task.updatedAt());
    }
}