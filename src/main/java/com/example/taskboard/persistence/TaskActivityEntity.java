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
 * One audit line in a task's history.
 *
 * <p>{@code taskId} is a plain column, not a foreign key. That is the whole point: when a task is
 * deleted its activity rows stay behind, so "who deleted this and when" survives the deletion.
 * A foreign key would cascade or block, and both outcomes lose the evidence.
 */
@Entity
@Table(name = "task_activities",
        indexes = @Index(name = "idx_task_activities_task_id", columnList = "task_id"))
public class TaskActivityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskActivityType type;

    @Column(nullable = false, length = 200)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    protected TaskActivityEntity() {
        // Required by JPA.
    }

    public TaskActivityEntity(long taskId, TaskActivityType type, String detail, Instant createdAt) {
        this.taskId = taskId;
        this.type = type;
        this.detail = detail;
        this.createdAt = createdAt;
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