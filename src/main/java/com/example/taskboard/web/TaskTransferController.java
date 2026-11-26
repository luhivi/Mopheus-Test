package com.example.taskboard.web;

import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.service.ImportExportService;
import com.example.taskboard.service.ImportOutcome;
import com.example.taskboard.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Import, export, activities and batch operations.
 *
 * <p>Separate from {@link TaskController} so the seven original endpoints keep their exact
 * declarations and route order untouched: {@code /stats} winning over {@code /{id}} depends on
 * literal-before-variable matching, and mixing seven new paths into that class makes a future
 * edit far more likely to break it. Both classes map under {@code /api/tasks}.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskTransferController {

    /** The filename shape is part of the contract the frontend parses, so it lives in one place. */
    private static final DateTimeFormatter EXPORT_FILENAME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Contract cap for one batch call, also stated in the README and the frontend. */
    private static final int MAX_BATCH_IDS = 200;

    private final TaskService taskService;
    private final ImportExportService importExportService;
    private final Clock clock;

    public TaskTransferController(TaskService taskService, ImportExportService importExportService, Clock clock) {
        this.taskService = taskService;
        this.importExportService = importExportService;
        this.clock = clock;
    }

    @GetMapping("/{id}/activities")
    public List<ActivityResponse> activities(
            @PathVariable long id,
            @RequestParam(required = false) String limit) {
        int effectiveLimit = parseLimit(limit);
        return taskService.activities(id, effectiveLimit).stream()
                .map(ActivityResponse::from)
                .toList();
    }

    @GetMapping("/export")
    public ResponseEntity<ImportExportService.ExportFile> export() {
        ImportExportService.ExportFile file = importExportService.export();
        String filename = "taskboard-export-" + EXPORT_FILENAME.format(java.time.LocalDateTime.now(clock)) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                // Only this form, so the browser's download link and the frontend's parser agree.
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(file);
    }

    @PostMapping("/import")
    public ImportOutcomeResponse importTasks(@RequestBody ImportRequest request) {
        ImportOutcome outcome = importExportService.importTasks(request.mode(), request.confirm(), request.tasks());
        return new ImportOutcomeResponse(outcome.mode(), outcome.imported(), outcome.skipped(), outcome.total());
    }

    @PostMapping("/batch-status")
    public Map<String, Object> batchStatus(@RequestBody JsonNode body) {
        BatchIds ids = parseBatchIds(body);
        TaskStatus status = parseBatchStatus(body);
        LocalDate today = LocalDate.now(clock);
        List<Map<String, Object>> results = taskService.batchStatus(ids.values(), status).stream()
                .map(outcome -> BatchResultResponse.of(outcome, today, true))
                .toList();
        return Map.of("results", results);
    }

    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody JsonNode body) {
        BatchIds ids = parseBatchIds(body);
        LocalDate today = LocalDate.now(clock);
        List<Map<String, Object>> results = taskService.batchDelete(ids.values()).stream()
                .map(outcome -> BatchResultResponse.of(outcome, today, false))
                .toList();
        return Map.of("results", results);
    }

    /**
     * Request-level batch validation.
     *
     * <p>Parsed from a {@link JsonNode} rather than bound with {@code @Valid} deliberately: a
     * missing, non-array, null-containing or non-numeric {@code ids} would fail Jackson binding
     * itself, and the framework reports that as {@code MALFORMED_BODY}. The contract for batch
     * requests is one code — {@code VALIDATION_FAILED} — because the frontend has a single 400
     * branch for batches, and a body that names the wrong shape is still a validation failure.
     */
    private static BatchIds parseBatchIds(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ValidationFailedException("request body must be a JSON object");
        }
        JsonNode raw = body.get("ids");
        if (raw == null || !raw.isArray()) {
            throw new ValidationFailedException("ids must be an array");
        }
        if (raw.isEmpty() || raw.size() > MAX_BATCH_IDS) {
            throw new ValidationFailedException(
                    "ids must contain between 1 and " + MAX_BATCH_IDS + " entries");
        }
        List<Long> ids = new java.util.ArrayList<>(raw.size());
        for (JsonNode element : raw) {
            // An id that is not a whole number is a malformed request, not a per-row failure:
            // there is no id to put in a result row.
            if (!element.isIntegralNumber() || !element.canConvertToLong()) {
                throw new ValidationFailedException("ids must contain only integer ids");
            }
            ids.add(element.asLong());
        }
        return new BatchIds(ids);
    }

    private static TaskStatus parseBatchStatus(JsonNode body) {
        JsonNode raw = body.get("status");
        if (raw == null || !raw.isTextual()) {
            throw new ValidationFailedException("status must be one of TODO, DOING, BLOCKED, DONE");
        }
        try {
            return TaskStatus.valueOf(raw.asText().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationFailedException("status must be one of TODO, DOING, BLOCKED, DONE");
        }
    }

    private record BatchIds(List<Long> values) {
    }

    /**
     * Missing means 50; anything that is not an integer in 1..200 is a malformed query. A
     * negative or zero limit is not "return nothing": that would look like an empty history and
     * hide the caller's mistake instead of reporting it.
     */
    private static int parseLimit(String raw) {
        if (raw == null) {
            return 50;
        }
        String trimmed = raw.trim();
        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            throw new MalformedQueryException("limit", raw);
        }
        if (value < 1 || value > 200) {
            throw new MalformedQueryException("limit", raw);
        }
        return value;
    }

    public record ActivityResponse(long id, long taskId, String type, String detail, java.time.Instant createdAt) {
        static ActivityResponse from(TaskActivity activity) {
            return new ActivityResponse(activity.id(), activity.taskId(), activity.type().name(),
                    activity.detail(), activity.createdAt());
        }
    }

    public record ImportOutcomeResponse(String mode, int imported, int skipped, long total) {
    }
}