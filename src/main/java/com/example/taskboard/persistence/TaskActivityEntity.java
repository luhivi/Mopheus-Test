package com.example.taskboard.persistence;

import com.example.taskboard.model.TaskActivityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Audit row for a task. {@code taskId} is deliberately not a foreign key: deleting a task must
 * leave its history behind, and a constraint would either block the delete or cascade the rows
 * away — both destroy the evidence the activity feed exists to keep.
 */
@Entity
@Table(name = "task_activity",
        indexes = @Index(name = "idx_task_activity_task", columnList = "task_id, created_at, id"))
public class TaskActivityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TaskActivityType type;

    @Column(length = 200)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    protected TaskActivityEntity() {
        // Required by JPA.
    }

    public static TaskActivityEntity of(long taskId, TaskActivityType type, String detail, Instant createdAt) {
        TaskActivityEntity entity = new TaskActivityEntity();
        entity.taskId = taskId;
        entity.type = type;
        entity.detail = detail;
        entity.createdAt = createdAt;
        return entity;
    }

    public Long getId() {
        return id;
    }

    public long getTaskId() {
        return taskId;
    }

    public TaskActivityType getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}