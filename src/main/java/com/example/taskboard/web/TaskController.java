package com.example.taskboard.web;

import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.service.TaskQuery;
import com.example.taskboard.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final Clock clock;

    public TaskController(TaskService taskService, Clock clock) {
        this.taskService = taskService;
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
}
