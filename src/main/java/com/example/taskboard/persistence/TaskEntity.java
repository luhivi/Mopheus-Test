package com.example.taskboard.persistence;

import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Stored form of a task. The domain object stays {@link com.example.taskboard.model.Task}; this
 * class exists only so storage concerns (identity, versions, column names, tag ordering) never
 * leak into the record the service and the wire format are written against.
 *
 * <p>Enums are stored by name, not ordinal: inserting a new constant in the middle of
 * {@code TaskStatus} would silently reinterpret every existing row if ordinals were used.
 * {@code tags} is eager because {@code spring.jpa.open-in-view=false} closes the session before
 * the response is rendered, so a lazy collection would fail outside the transaction and turn
 * every read into a 500.
 */
@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskPriority priority;

    @Column(length = 40)
    private String assignee;

    private LocalDate dueDate;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_tags", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "position")
    @Column(name = "tag", nullable = false, length = 20)
    private List<String> tags = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock. A concurrent edit that read the same row version fails on flush instead
     * of silently overwriting, which is the difference between "409, try again" and "someone's
     * work vanished".
     */
    @Version
    private long version;

    protected TaskEntity() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    /**
     * Mutable on purpose: replacing the collection instance on a managed entity is the classic
     * way to leave orphan rows behind, so callers mutate these two methods instead.
     */
    public List<String> getTags() {
        return tags;
    }

    public void replaceTags(List<String> newTags) {
        tags.clear();
        tags.addAll(newTags);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }
}