package com.example.taskboard.persistence;

import com.example.taskboard.model.Task;
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
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Stored form of a task.
 *
 * <p>Kept separate from the {@link Task} record on purpose. The record is the domain object the
 * service, the state machine, the filter and the sorting all reason about; it has no JPA
 * lifecycle, no setters and cannot be lazily proxied. Mapping between the two lives in
 * {@link #toDomain()} and the service's field-by-field apply, so a persistence change never
 * silently alters the wire contract.
 */
@Entity
@Table(name = "tasks")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String title;

    // EnumType.STRING on every enum, without exception: the default is ORDINAL, which stores a
    // position in the enum. Reordering or inserting a constant would then silently reinterpret
    // every stored row — a rename is a code change, a reorder is silent data corruption.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskPriority priority;

    @Column(length = 40)
    private String assignee;

    private LocalDate dueDate;

    // EAGER is not laziness: with spring.jpa.open-in-view=false the session is closed when the
    // service returns, so a LAZY collection would fail on every GET. @OrderColumn is what keeps
    // the first-seen order of tags, which the API has always returned and the UI renders as-is.
    //
    // @BatchSize turns the list endpoints from N+1 into a handful of queries: 600 tasks with an
    // EAGER collection is 1 query for the tasks plus 600 for their tags, which is visible as a
    // slow board and was measured as 601 SELECTs. Batching fetches the same rows 50 owners at a
    // time (13 queries instead of 601). EAGER stays — the fetch type is part of the contract; only
    // the number of round trips changes.
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_tags", joinColumns = @JoinColumn(name = "task_id"))
    @OrderColumn(name = "tag_order")
    @Column(name = "tag", nullable = false, length = 20)
    private List<String> tags = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // Optimistic locking: two tabs editing the same task must not silently overwrite each other.
    // A stale write fails the version check at flush and surfaces as 409 CONCURRENT_MODIFICATION.
    @Version
    private Long version;

    protected TaskEntity() {
        // Required by JPA.
    }

    /** Blank instance for a task that does not exist yet; id and version stay unset. */
    public static TaskEntity newTask() {
        return new TaskEntity();
    }

    public Task toDomain() {
        return new Task(
                id == null ? 0L : id,
                title,
                status,
                priority,
                assignee,
                dueDate,
                List.copyOf(tags),
                createdAt,
                updatedAt);
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

    public List<String> getTags() {
        return tags;
    }

    /**
     * Mutates the existing collection rather than replacing it. Hibernate holds a reference to the
     * persistent collection it materialised; assigning a new list leaves it tracking the old one,
     * so the change is silently not flushed on an update.
     */
    public void setTags(List<String> tags) {
        this.tags.clear();
        this.tags.addAll(tags);
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

    public Long getVersion() {
        return version;
    }
}