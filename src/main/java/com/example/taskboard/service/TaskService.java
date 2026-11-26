package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskActivityType;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.persistence.TaskActivityEntity;
import com.example.taskboard.persistence.TaskActivityRepository;
import com.example.taskboard.persistence.TaskEntity;
import com.example.taskboard.persistence.TaskRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.taskboard.web.ValidationFailedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Task use cases. Storage is a database now, but everything that decides *what* a task is — the
 * normalisation rules, the strict-PUT semantics, the transition table, the history suppression
 * rules — lives here, unchanged from the in-memory version. The repository only answers "which
 * rows".
 */
@Service
public class TaskService {

    /** Mirrors the documented import cap; enforced before any row is looked at. */
    public static final int IMPORT_MAX_ROWS = 1000;

    private final TaskRepository tasks;
    private final TaskActivityRepository activities;
    private final Clock clock;

    public TaskService(TaskRepository tasks, TaskActivityRepository activities, Clock clock) {
        this.tasks = tasks;
        this.activities = activities;
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

    /**
     * One row of an import file. Enum and date values arrive as text on purpose: a single bad name
     * must skip that row, and a strongly typed field would fail the whole request before any row
     * could be counted as skipped.
     *
     * <p>{@code malformed} marks a row whose JSON had a value of the wrong type (a number where a
     * status belongs, a string where the tag array belongs). The controller detects that; this
     * layer just needs to know the row is unusable, because a wrong type parsed to null would
     * otherwise look identical to an omitted field and import with a default.
     */
    public record ImportRow(
            String title,
            String status,
            String priority,
            String assignee,
            String dueDate,
            List<String> tags,
            boolean malformed) {
    }

    public record ImportResult(String mode, int imported, int skipped, long total) {
    }

    public record ExportData(Instant exportedAt, List<Task> tasks) {
    }

    @Transactional(readOnly = true)
    public List<Task> findAll(TaskQuery query) {
        TaskQuery effective = query == null
                ? new TaskQuery(null, null, null, null, null, null, true, null)
                : query;
        List<Task> domain = tasks.findAll().stream().map(TaskEntity::toDomain).toList();
        return effective.apply(new ArrayList<>(domain));
    }

    @Transactional(readOnly = true)
    public Task findById(long id) {
        return require(id).toDomain();
    }

    @Transactional
    public Task create(Draft draft) {
        return createWithStatus(draft, TaskStatus.TODO);
    }

    /**
     * Full replacement of the editable fields. Status is not editable here: it is owned by
     * {@link #updateStatus} so a status change can never bypass the transition table.
     *
     * <p>Semantics are strict PUT: a field absent from the request is cleared, not kept.
     *
     * <p>After normalisation, a request that changes nothing writes nothing at all: no row update,
     * no {@code updatedAt} bump, no history line. Bumping the timestamp on a no-op edit would make
     * "saved twice" indistinguishable from a real edit in the history, and would contradict the
     * idempotent status patch sitting next to it.
     */
    @Transactional
    public Task update(long id, Draft draft) {
        TaskEntity entity = require(id);
        Instant now = Instant.now(clock);

        String title = normalizeTitle(draft.title());
        TaskPriority priority = draft.priority() == null ? TaskPriority.NORMAL : draft.priority();
        String assignee = normalizeAssignee(draft.assignee());
        LocalDate dueDate = draft.dueDate();
        List<String> tags = normalizeTags(draft.tags());

        List<String> changed = new ArrayList<>();
        if (!Objects.equals(entity.getTitle(), title)) {
            changed.add("标题");
        }
        if (entity.getPriority() != priority) {
            changed.add("优先级");
        }
        if (!Objects.equals(entity.getAssignee(), assignee)) {
            changed.add("负责人");
        }
        if (!Objects.equals(entity.getDueDate(), dueDate)) {
            changed.add("截止日期");
        }
        // Order-insensitive. The tags are a set of labels, not a ranked list, and the edit dialog
        // posts the whole form back: with a positional comparison, a user who only retyped the
        // assignee still produced a "修改字段：标签" history entry whenever the browser's tag order
        // differed from the stored one. Duplicates are already removed by normalizeTags, so two
        // same-size HashSets are the right test.
        if (!java.util.Set.copyOf(entity.getTags()).equals(java.util.Set.copyOf(tags))) {
            changed.add("标签");
        }
        if (changed.isEmpty()) {
            return entity.toDomain();
        }

        entity.setTitle(title);
        entity.setPriority(priority);
        entity.setAssignee(assignee);
        entity.setDueDate(dueDate);
        entity.setTags(tags);
        entity.setUpdatedAt(now);
        recordActivity(id, TaskActivityType.UPDATED, "修改字段：" + String.join("、", changed), now);
        return entity.toDomain();
    }

    @Transactional
    public Task updateStatus(long id, TaskStatus target) {
        TaskEntity entity = require(id);
        TaskStatus current = entity.getStatus();
        if (!current.canMoveTo(target)) {
            throw new InvalidTaskTransitionException(id, current.name(), String.valueOf(target));
        }
        if (current == target) {
            // Idempotent no-op: keep the row as is, including updatedAt, and write no history.
            // Drag-and-drop, retries and a stale second tab all produce this request; none of them
            // is a change anyone would want to read back as one.
            return entity.toDomain();
        }

        Instant now = Instant.now(clock);
        entity.setStatus(target);
        entity.setUpdatedAt(now);
        recordActivity(id, TaskActivityType.STATUS_CHANGED, current.name() + " → " + target.name(), now);
        return entity.toDomain();
    }

    /**
     * Deletes the task row and keeps its history. The activity row is not a foreign key, so the
     * "who removed this" line survives the deletion — which is the whole point of keeping it.
     */
    @Transactional
    public void delete(long id) {
        TaskEntity entity = require(id);
        recordActivity(id, TaskActivityType.DELETED, "删除任务", Instant.now(clock));
        tasks.delete(entity);
    }

    @Transactional(readOnly = true)
    public TaskStats stats(LocalDate today) {
        List<Task> all = tasks.findAll().stream().map(TaskEntity::toDomain).toList();
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
     * History for one task id. A task whose row is gone still answers as long as it left history
     * behind: the audit trail is precisely what a deleted task is still worth.
     */
    @Transactional(readOnly = true)
    public List<TaskActivity> activities(long id, int limit) {
        List<TaskActivityEntity> rows =
                activities.findByTaskIdOrderByCreatedAtDescIdDesc(id, PageRequest.of(0, limit));
        if (rows.isEmpty() && !tasks.existsById(id)) {
            throw new TaskNotFoundException(id);
        }
        return rows.stream()
                .map(row -> new TaskActivity(row.getId(), row.getTaskId(), row.getType(),
                        row.getDetail(), row.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExportData export() {
        return new ExportData(Instant.now(clock), findAll(null));
    }

    /**
     * Bulk import. Each row is validated on its own: a bad row is skipped and counted, never a
     * request-level failure, because one malformed line in a 500-row backup must not cost the
     * other 499.
     *
     * <p>{@code replace} is a single transaction: clear, then insert. A failure halfway through
     * leaves the previous data intact instead of an empty board.
     *
     * <p>Validation runs to completion <em>before</em> the clear. Doing it in one pass — clear,
     * then validate each row as it is written — meant a replace whose every row was invalid still
     * wiped the board and its history, then reported "imported 0" with a 200. The user would have
     * destroyed their data and been told it worked. Clearing is therefore gated on at least one
     * valid row.
     */
    @Transactional
    public ImportResult importTasks(String mode, List<ImportRow> rows) {
        List<ValidRow> valid = new ArrayList<>(rows.size());
        int skipped = 0;
        for (ImportRow row : rows) {
            ValidRow checked = validateRow(row);
            if (checked == null) {
                skipped++;
            } else {
                valid.add(checked);
            }
        }

        if ("replace".equals(mode)) {
            if (valid.isEmpty()) {
                // Nothing to write, so there is nothing a clear could accomplish except losing
                // data. Refusing here keeps the board and its history exactly as they were.
                throw new ValidationFailedException("replace would clear the board but no row is valid");
            }
            // Tag rows carry a foreign key to tasks and must go first, or the bulk delete fails.
            // History goes with the tasks: the board is being rebuilt from a file, and keeping the
            // old history would leave rows attributed to ids that now mean different tasks.
            tasks.deleteAllTagRows();
            tasks.deleteAllTasks();
            activities.deleteAllInBatch();
        }

        int imported = 0;
        for (ValidRow row : valid) {
            createWithStatus(row.draft(), row.status());
            imported++;
        }
        return new ImportResult(mode, imported, skipped, tasks.count());
    }

    /** A row that survived every check, ready to be written. */
    private record ValidRow(Draft draft, TaskStatus status) {
    }

    /**
     * Turns a wire row into a writable one, or {@code null} when the row must be skipped.
     *
     * <p>Pure and side-effect free: it is what lets the caller decide whether to clear at all, so
     * it must not touch the store.
     */
    private ValidRow validateRow(ImportRow row) {
        if (row.malformed()) {
            return null;
        }
        String title = row.title() == null ? "" : row.title().trim();
        TaskStatus status = parseStatus(row.status());
        TaskPriority priority = parsePriority(row.priority());
        ParsedDate dueDate = parseDueDate(row.dueDate());
        String assignee = normalizeAssignee(row.assignee());
        List<String> tags = normalizeTags(row.tags());

        // A title over 80 or an assignee over 40 is skipped for the same reason a blank title
        // is: the interactive API refuses those values, so accepting them on import would put
        // a task on the board that can never be saved again from the edit dialog.
        if (title.isEmpty() || title.length() > 80
                || status == null || priority == null || !dueDate.valid()
                || (assignee != null && assignee.length() > 40)
                || tags.size() > 5
                || tags.stream().anyMatch(tag -> tag.length() > 20)) {
            return null;
        }
        return new ValidRow(new Draft(title, priority, assignee, dueDate.value(), tags), status);
    }

    /**
     * Result of parsing an optional date. Three states matter — absent (legal, no deadline),
     * valid, and unparseable (row must be skipped) — and a nullable {@link LocalDate} can only
     * express two of them. A sentinel date would collide with a genuinely valid input.
     */
    private record ParsedDate(LocalDate value, boolean valid) {
        static ParsedDate absent() {
            return new ParsedDate(null, true);
        }

        static ParsedDate invalid() {
            return new ParsedDate(null, false);
        }

        static ParsedDate of(LocalDate value) {
            return new ParsedDate(value, true);
        }
    }

    /**
     * Create with an explicit starting status. Only import may choose one: the interactive path
     * always starts at TODO so no client can skip the transition table.
     *
     * <p>Not annotated: it is called from {@link #create} and from {@link #importTasks}, both of
     * which already run in a transaction. A {@code @Transactional} here would be dead weight, and
     * a self-invocation on {@code this} would not proxy anyway — the caller's transaction is what
     * actually applies, which is exactly what import's atomicity relies on.
     */
    Task createWithStatus(Draft draft, TaskStatus status) {
        Instant now = Instant.now(clock);
        TaskEntity entity = TaskEntity.newTask();
        entity.setTitle(normalizeTitle(draft.title()));
        entity.setStatus(status == null ? TaskStatus.TODO : status);
        entity.setPriority(draft.priority() == null ? TaskPriority.NORMAL : draft.priority());
        entity.setAssignee(normalizeAssignee(draft.assignee()));
        entity.setDueDate(draft.dueDate());
        entity.setTags(normalizeTags(draft.tags()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        TaskEntity saved = tasks.save(entity);
        recordActivity(saved.getId(), TaskActivityType.CREATED, "创建任务", now);
        return saved.toDomain();
    }

    private TaskEntity require(long id) {
        return tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    private void recordActivity(long taskId, TaskActivityType type, String detail, Instant at) {
        activities.save(new TaskActivityEntity(taskId, type, detail, at));
    }

    /** Blank means "not supplied", which defaults to TODO; an unknown name means the row is bad. */
    private static TaskStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return TaskStatus.TODO;
        }
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static TaskPriority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return TaskPriority.NORMAL;
        }
        try {
            return TaskPriority.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Blank means "no deadline"; an unparseable value marks the row as invalid. */
    private static ParsedDate parseDueDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedDate.absent();
        }
        try {
            return ParsedDate.of(LocalDate.parse(raw.trim()));
        } catch (DateTimeParseException ex) {
            return ParsedDate.invalid();
        }
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