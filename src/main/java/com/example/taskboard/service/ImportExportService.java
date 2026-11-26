package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.web.ImportTaskPayload;
import com.example.taskboard.web.TaskResponse;
import com.example.taskboard.web.ReplaceNotConfirmedException;
import com.example.taskboard.web.ValidationFailedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Import and export.
 *
 * <p>Separate from {@link TaskService} because the rules are different in one important way: an
 * import may land a task in any legal status, skipping the interactive transition table. That is
 * the restore path, and routing it through the normal create/status endpoints would either be
 * impossible (DONE is unreachable from TODO) or would fabricate a fake walk through the state
 * machine in the activity log.
 */
@Service
public class ImportExportService {

    static final int MAX_ROWS = 1000;
    private static final int MAX_TITLE = 80;
    private static final int MAX_ASSIGNEE = 40;
    private static final int MAX_TAGS = 5;
    private static final int MAX_TAG_LENGTH = 20;

    private final TaskService taskService;
    private final TaskStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ImportExportService(TaskService taskService, TaskStore store, ObjectMapper objectMapper, Clock clock) {
        this.taskService = taskService;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Snapshot for the export file: every task, in default board order, plus the export moment. */
    public record ExportFile(Instant exportedAt, int count, List<TaskResponse> tasks) {
    }

    public ExportFile export() {
        // Exported with the same TaskResponse shape a GET returns, including the computed
        // overdue fields. A file that is missing fields the API otherwise always provides makes
        // "全字段" a lie and forces every consumer to special-case the export document.
        LocalDate today = LocalDate.now(clock);
        List<TaskResponse> rows = taskService.findAll(null).stream()
                .map(task -> TaskResponse.from(task, today))
                .toList();
        return new ExportFile(Instant.now(clock), rows.size(), rows);
    }

    /**
     * Validates and applies an import.
     *
     * <p>Row failures are counted, never fatal: one bad line in a hand-edited file must not stop
     * the other 900 from restoring. Only request-shape problems (missing/non-array/oversized
     * {@code tasks}, unknown {@code mode}, unconfirmed replace) reject the request as a whole.
     */
    public ImportOutcome importTasks(String mode, String confirm, JsonNode tasks) {
        ImportMode parsedMode = parseMode(mode);
        if (tasks == null || !tasks.isArray()) {
            throw new ValidationFailedException("tasks must be an array");
        }
        int rows = tasks.size();
        if (rows > MAX_ROWS) {
            throw new ValidationFailedException("tasks must contain at most " + MAX_ROWS + " entries");
        }
        if (parsedMode == ImportMode.REPLACE && !"replace".equals(confirm)) {
            throw new ReplaceNotConfirmedException();
        }

        List<Task> valid = new ArrayList<>();
        int skipped = 0;
        for (JsonNode row : tasks) {
            Task task = parseRow(row);
            if (task == null) {
                skipped++;
            } else {
                valid.add(task);
            }
        }

        List<Task> written = parsedMode == ImportMode.REPLACE
                // One transaction: clearing and writing either both happen or neither does.
                ? store.replaceAll(valid)
                : store.insertAll(valid);

        return new ImportOutcome(parsedMode.wireName(), written.size(), skipped, store.count());
    }

    private enum ImportMode {
        MERGE("merge"),
        REPLACE("replace");

        private final String wireName;

        ImportMode(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    private static ImportMode parseMode(String mode) {
        if (mode == null) {
            throw new ValidationFailedException("mode must be merge or replace");
        }
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "merge" -> ImportMode.MERGE;
            case "replace" -> ImportMode.REPLACE;
            default -> throw new ValidationFailedException("mode must be merge or replace");
        };
    }

    /**
     * Returns the task to write, or {@code null} when this row must be skipped.
     *
     * <p>A row is bound through {@link ImportTaskPayload} so unknown keys (the {@code id},
     * {@code createdAt}, {@code updatedAt}, {@code overdue} and {@code daysOverdue} an export
     * carries) are ignored rather than rejected, and so a re-import of an exported file is always
     * accepted.
     */
    private Task parseRow(JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }
        ImportTaskPayload payload;
        try {
            payload = objectMapper.treeToValue(row, ImportTaskPayload.class);
        } catch (JsonProcessingException ex) {
            // A row whose JSON types do not match the payload shape is a skipped row, not a
            // failed request: this is exactly the "逐条校验" rule. Catching Jackson's own
            // exception type keeps a genuine bug elsewhere from being swallowed as "bad row".
            return null;
        }
        if (payload == null) {
            return null;
        }

        String title = payload.title() == null ? "" : payload.title().trim();
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            return null;
        }

        TaskStatus status = parseStatus(payload.status());
        if (status == null) {
            return null;
        }
        TaskPriority priority = parsePriority(payload.priority());
        if (priority == null) {
            return null;
        }

        String assignee = payload.assignee() == null ? null : payload.assignee().trim();
        if (assignee != null && assignee.isEmpty()) {
            assignee = null;
        }
        if (assignee != null && assignee.length() > MAX_ASSIGNEE) {
            return null;
        }

        LocalDate dueDate;
        try {
            dueDate = parseDate(payload.dueDate());
        } catch (DateTimeParseException ex) {
            return null;
        }

        List<String> tags = normalizeValidatedTags(payload.tags());
        if (tags == null) {
            return null;
        }

        Instant now = Instant.now(clock);
        // id, createdAt and updatedAt from the file are discarded on purpose: an imported row is
        // a new row, and reusing an id would either collide with an existing task (merge) or make
        // the id sequence disagree with the stored ids.
        return new Task(0L, title, status, priority, assignee, dueDate, tags, now, now);
    }

    /** Absent status means TODO, matching create. An unknown name skips the row. */
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

    /** Absent priority means NORMAL, matching create. An unknown name skips the row. */
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

    /** Absent or blank due date means "no due date". An unparseable value throws. */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LocalDate.parse(raw.trim());
    }

    /**
     * Normalises first, then validates — the order the contract specifies. Checking before
     * trimming would reject {@code ["  a  ", "a", " "]}, which normalises to a single legal tag,
     * and would also let five blanks plus a real tag through as "six tags".
     *
     * @return the normalised tags, or {@code null} when the row must be skipped
     */
    private static List<String> normalizeValidatedTags(List<String> raw) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (raw != null) {
            for (String tag : raw) {
                if (tag == null) {
                    continue;
                }
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    unique.add(trimmed);
                }
            }
        }
        if (unique.size() > MAX_TAGS) {
            return null;
        }
        for (String tag : unique) {
            if (tag.length() > MAX_TAG_LENGTH) {
                return null;
            }
        }
        return List.copyOf(unique);
    }
}