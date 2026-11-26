package com.example.taskboard.web;

import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.service.TaskBatchService;
import com.example.taskboard.service.TaskQuery;
import com.example.taskboard.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final int ACTIVITY_DEFAULT_LIMIT = 50;
    private static final int ACTIVITY_MAX_LIMIT = 200;
    private static final int BATCH_MAX_IDS = 200;
    private static final DateTimeFormatter EXPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TaskService taskService;
    private final TaskBatchService batchService;
    private final Clock clock;

    public TaskController(TaskService taskService, TaskBatchService batchService, Clock clock) {
        this.taskService = taskService;
        this.batchService = batchService;
        this.clock = clock;
    }

    @GetMapping
    public List<TaskResponse> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> priority,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "desc") String order) {

        LocalDate today = LocalDate.now(clock);
        TaskQuery query = new TaskQuery(
                parseStatuses(status),
                parsePriorities(priority),
                q,
                assignee,
                overdue,
                parseSort(sort),
                // Anything other than an explicit "asc" keeps the default newest-first behaviour,
                // because a typo in this parameter should not silently reverse the board.
                !"asc".equalsIgnoreCase(order == null ? "" : order.trim()),
                today);

        return taskService.findAll(query).stream()
                .map(task -> TaskResponse.from(task, today))
                .toList();
    }

    private static List<TaskStatus> parseStatuses(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<TaskStatus> parsed = new ArrayList<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                parsed.add(TaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new MalformedQueryException("status", value);
            }
        }
        return parsed;
    }

    private static List<TaskPriority> parsePriorities(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<TaskPriority> parsed = new ArrayList<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                parsed.add(TaskPriority.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new MalformedQueryException("priority", value);
            }
        }
        return parsed;
    }

    private static TaskQuery.SortKey parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return TaskQuery.SortKey.CREATED_AT;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "createdat", "created_at", "created" -> TaskQuery.SortKey.CREATED_AT;
            case "duedate", "due_date", "due" -> TaskQuery.SortKey.DUE_DATE;
            case "priority" -> TaskQuery.SortKey.PRIORITY;
            default -> throw new MalformedQueryException("sort", raw);
        };
    }

    /**
     * Declared before {@code /{id}} on purpose: Spring's path matching would otherwise hand the
     * literal {@code stats} to the {@code {id}} mapping and fail to convert it to a long.
     */
    @GetMapping("/stats")
    public TaskStatsResponse stats() {
        LocalDate today = LocalDate.now(clock);
        return TaskStatsResponse.from(taskService.stats(today));
    }

    @GetMapping("/export")
    public ResponseEntity<ExportResponse> export() {
        LocalDate today = LocalDate.now(clock);
        TaskService.ExportData data = taskService.export();
        String filename = "taskboard-export-"
                + LocalDateTime.now(clock).format(EXPORT_STAMP) + ".json";
        // Content-Disposition via the builder, not a hand-built string: the filename is a fixed
        // pattern, but the builder is what guarantees the quoted, RFC 6266-shaped header.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ExportResponse.from(data.exportedAt(), data.tasks(), today));
    }

    @PostMapping("/import")
    public ImportResponse importTasks(@Valid @RequestBody ImportRequest request) {
        String mode = request.mode() == null ? null : request.mode().trim().toLowerCase(Locale.ROOT);
        if (!"merge".equals(mode) && !"replace".equals(mode)) {
            throw new ValidationFailedException("mode must be one of merge, replace");
        }
        JsonNode rows = request.tasks();
        if (rows == null || !rows.isArray()) {
            throw new ValidationFailedException("tasks must be an array");
        }
        if (rows.size() > TaskService.IMPORT_MAX_ROWS) {
            throw new ValidationFailedException(
                    "tasks must contain at most " + TaskService.IMPORT_MAX_ROWS + " items");
        }
        // Replace wipes the board; the token makes that intent explicit in the request instead of
        // inferring it, so a stray script cannot delete everything by sending mode alone.
        if ("replace".equals(mode) && !"replace".equals(request.confirm())) {
            throw new ReplaceNotConfirmedException();
        }

        List<TaskService.ImportRow> parsed = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            parsed.add(new TaskService.ImportRow(
                    text(row, "title"),
                    text(row, "status"),
                    text(row, "priority"),
                    text(row, "assignee"),
                    text(row, "dueDate"),
                    textList(row, "tags"),
                    rowHasWronglyTypedField(row)));
        }

        TaskService.ImportResult result = taskService.importTasks(mode, parsed);
        return new ImportResponse(result.mode(), result.imported(), result.skipped(), result.total());
    }

    @PostMapping("/batch-status")
    public BatchResponse batchStatus(@Valid @RequestBody BatchRequest request) {
        List<Long> ids = parseIds(request.ids());
        TaskStatus target = parseBatchStatus(request.status());
        LocalDate today = LocalDate.now(clock);

        List<BatchResultItem> results = new ArrayList<>(ids.size());
        for (TaskBatchService.Item item : batchService.updateStatus(ids, target)) {
            results.add(item.task() != null
                    ? BatchResultItem.success(item.id(), TaskResponse.from(item.task(), today))
                    : BatchResultItem.failure(item.id(), item.code(), item.message()));
        }
        return new BatchResponse(results);
    }

    @PostMapping("/batch-delete")
    public BatchResponse batchDelete(@Valid @RequestBody BatchRequest request) {
        List<Long> ids = parseIds(request.ids());

        List<BatchResultItem> results = new ArrayList<>(ids.size());
        for (TaskBatchService.Item item : batchService.delete(ids)) {
            results.add(item.code() != null
                    ? BatchResultItem.failure(item.id(), item.code(), item.message())
                    : BatchResultItem.success(item.id()));
        }
        return new BatchResponse(results);
    }

    @GetMapping("/{id}/activities")
    public List<TaskActivityResponse> activities(
            @PathVariable long id,
            @RequestParam(required = false) String limit) {
        int effective = parseActivityLimit(limit);
        return taskService.activities(id, effective).stream()
                .map(TaskController::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable long id) {
        LocalDate today = LocalDate.now(clock);
        return TaskResponse.from(taskService.findById(id), today);
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskPayload payload) {
        LocalDate today = LocalDate.now(clock);
        TaskService.Draft draft = new TaskService.Draft(payload.title(), payload.priority(), payload.assignee(),
                payload.dueDate(), payload.tags());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TaskResponse.from(taskService.create(draft), today));
    }

    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable long id, @Valid @RequestBody TaskPayload payload) {
        LocalDate today = LocalDate.now(clock);
        TaskService.Draft draft = new TaskService.Draft(payload.title(), payload.priority(), payload.assignee(),
                payload.dueDate(), payload.tags());
        return TaskResponse.from(taskService.update(id, draft), today);
    }

    @PatchMapping("/{id}/status")
    public TaskResponse updateStatus(@PathVariable long id, @Valid @RequestBody UpdateStatusRequest request) {
        LocalDate today = LocalDate.now(clock);
        return TaskResponse.from(taskService.updateStatus(id, request.status()), today);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        taskService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static TaskActivityResponse toResponse(TaskActivity activity) {
        return new TaskActivityResponse(activity.id(), activity.taskId(), activity.type(),
                activity.detail(), activity.createdAt());
    }

    /**
     * The limit arrives as a string so that one bad value produces the batch contract's
     * MALFORMED_QUERY instead of Spring's generic type-mismatch path. The parameter is
     * {@code String} rather than {@code Integer} precisely to keep that decision here.
     */
    private static int parseActivityLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return ACTIVITY_DEFAULT_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new MalformedQueryException("limit", raw);
        }
        if (value < 1 || value > ACTIVITY_MAX_LIMIT) {
            throw new MalformedQueryException("limit", raw);
        }
        return value;
    }

    /**
     * Canonicalises the id list: duplicates collapse to their first position, so the answer's row
     * order is the request's order and a client can line the two up without a lookup table.
     */
    private static List<Long> parseIds(JsonNode ids) {
        if (ids == null || !ids.isArray()) {
            throw new ValidationFailedException("ids must be an array");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (JsonNode node : ids) {
            if (!node.isIntegralNumber() || !node.canConvertToLong()) {
                throw new ValidationFailedException("ids must contain only integers");
            }
            unique.add(node.longValue());
        }
        if (unique.isEmpty()) {
            throw new ValidationFailedException("ids must contain at least 1 item");
        }
        if (unique.size() > BATCH_MAX_IDS) {
            throw new ValidationFailedException(
                    "ids must contain between 1 and " + BATCH_MAX_IDS + " items");
        }
        return List.copyOf(unique);
    }

    private static TaskStatus parseBatchStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationFailedException("status must not be blank");
        }
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationFailedException("status must be one of TODO, DOING, BLOCKED, DONE");
        }
    }

    /** Import rows are read leniently: an absent field is "not supplied", a bad name skips the row. */
    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.textValue() : null;
    }

    private static List<String> textList(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (item.isTextual()) {
                out.add(item.textValue());
            }
        }
        return out;
    }

    /**
     * A field present with the wrong JSON type is a row-level error, not an omission.
     *
     * <p>Without this, {@code {"status":2}} would read as "no status" and import as TODO, and a
     * number where tags belong would read as "no tags" — both silently rewriting the caller's
     * data. The spec's "illegal enum or date skips the row" has to cover the type as well as the
     * value, or the strictness it asks for leaks away at exactly the layer that bypasses Jackson.
     */
    private static boolean rowHasWronglyTypedField(JsonNode row) {
        for (String field : new String[]{"title", "status", "priority", "assignee", "dueDate"}) {
            JsonNode value = row.get(field);
            if (value != null && !value.isNull() && !value.isTextual()) {
                return true;
            }
        }
        JsonNode tags = row.get("tags");
        if (tags != null && !tags.isNull()) {
            if (!tags.isArray()) {
                return true;
            }
            for (JsonNode item : tags) {
                if (!item.isNull() && !item.isTextual()) {
                    return true;
                }
            }
        }
        return false;
    }
}