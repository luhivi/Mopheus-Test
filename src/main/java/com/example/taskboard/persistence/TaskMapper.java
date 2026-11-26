package com.example.taskboard.persistence;

import com.example.taskboard.model.Task;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Translates between the domain record and the stored entity.
 *
 * <p>Kept as a plain component rather than inside the entity so {@code Task} stays a record with
 * no storage annotations, and so the normalisation rules in {@code TaskService} keep operating on
 * the domain object instead of on managed JPA state.
 */
@Component
public class TaskMapper {

    public Task toDomain(TaskEntity entity) {
        return new Task(
                entity.getId() == null ? 0L : entity.getId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getPriority(),
                entity.getAssignee(),
                entity.getDueDate(),
                List.copyOf(entity.getTags()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** Builds a fresh entity. The id is left to the database so IDENTITY can assign it. */
    public TaskEntity toNewEntity(Task task) {
        TaskEntity entity = new TaskEntity();
        applyAll(entity, task);
        return entity;
    }

    /** Copies every stored field of {@code task} onto {@code entity}. The id is never reassigned. */
    public void applyAll(TaskEntity entity, Task task) {
        entity.setTitle(task.title());
        entity.setStatus(task.status());
        entity.setPriority(task.priority());
        entity.setAssignee(task.assignee());
        entity.setDueDate(task.dueDate());
        entity.replaceTags(task.tags());
        entity.setCreatedAt(task.createdAt());
        entity.setUpdatedAt(task.updatedAt());
    }
}