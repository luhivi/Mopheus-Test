package com.example.taskboard.persistence;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskActivityType;
import com.example.taskboard.service.TaskConflictException;
import com.example.taskboard.service.TaskNotFoundException;
import com.example.taskboard.service.TaskStore;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class JpaTaskStore implements TaskStore {

    private final TaskRepository tasks;
    private final TaskActivityRepository activities;
    private final TaskMapper mapper;
    private final Clock clock;

    public JpaTaskStore(TaskRepository tasks, TaskActivityRepository activities, TaskMapper mapper, Clock clock) {
        this.tasks = tasks;
        this.activities = activities;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> findAll() {
        return tasks.findAll().stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Task> findById(long id) {
        // Tags are eager, so the mapping is safe even though open-in-view is off: the collection
        // is loaded inside this transaction, never lazily during response rendering.
        return tasks.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return tasks.count();
    }

    @Override
    @Transactional
    public Task insert(Task task, TaskActivityType activityType, String activityDetail) {
        TaskEntity saved = tasks.save(mapper.toNewEntity(task));
        appendActivity(saved.getId(), activityType, activityDetail, task.createdAt());
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional
    public Task mutate(long id, Planner planner) {
        TaskEntity entity = tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
        Task current = mapper.toDomain(entity);
        Plan plan = planner.plan(current);

        if (plan.next().equals(current)) {
            // Zero difference after normalisation. Writing anyway would bump updatedAt and make
            // the board look changed when nothing was, so the row is left exactly as it was.
            return current;
        }

        mapper.applyAll(entity, plan.next());
        entity.setUpdatedAt(plan.next().updatedAt());
        try {
            tasks.flush();
        } catch (OptimisticLockingFailureException ex) {
            throw new TaskConflictException(id);
        }
        appendActivity(id, plan.activityType(), plan.activityDetail(), plan.next().updatedAt());
        return mapper.toDomain(entity);
    }

    @Override
    @Transactional
    public void delete(long id, TaskActivityType activityType, String activityDetail) {
        TaskEntity entity = tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
        // The activity row is written first and the task row second, but both live in this
        // transaction: there is no window in which a deleted task has no DELETED row.
        // Same Clock bean as the service, so a test that moves time sees the DELETED row move too.
        appendActivity(id, activityType, activityDetail, Instant.now(clock));
        tasks.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskActivity> activities(long taskId, int limit) {
        return activities.findRecent(taskId, PageRequest.of(0, limit)).stream()
                .map(entity -> new TaskActivity(entity.getId(), entity.getTaskId(), entity.getType(),
                        entity.getDetail(), entity.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public void clearAll() {
        // Order matters: tag rows first, then tasks. Activities are cleared with the tasks because
        // a replace import starts a new board and old history would point at ids that now belong
        // to different tasks.
        tasks.deleteAllTagRows();
        tasks.deleteAllTaskRows();
        activities.deleteAllActivities();
    }

    @Override
    @Transactional
    public List<Task> insertAll(List<Task> newTasks) {
        List<TaskEntity> entities = newTasks.stream().map(mapper::toNewEntity).toList();
        return tasks.saveAll(entities).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional
    public List<Task> replaceAll(List<Task> newTasks) {
        // One transaction covering both the clear and the insert. If the clear succeeded and the
        // insert failed, auto-commit per statement would leave the user with an empty board and
        // no copy of the file they just imported.
        clearAll();
        return insertAll(newTasks);
    }

    private void appendActivity(Long taskId, TaskActivityType type, String detail, Instant createdAt) {
        if (type == null) {
            return;
        }
        activities.save(TaskActivityEntity.of(taskId, type, detail, createdAt));
    }
}