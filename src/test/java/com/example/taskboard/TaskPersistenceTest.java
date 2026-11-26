package com.example.taskboard;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the JPA layer, distinct from the service-level tests in
 * {@code TaskServiceTest}, which run against an in-memory store.
 *
 * <p>These exist because the two stores can drift: if the entity mapping drops {@code tags} or
 * stores the enum by ordinal, the in-memory tests would still be green and only production would
 * be wrong. Every assertion here is about what the database actually holds.
 */
@SpringBootTest
class TaskPersistenceTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createdTaskSurvivesAFullReload() {
        Task created = taskService.create(new TaskService.Draft(
                "  persist me  ", TaskPriority.HIGH, "  alice  ", LocalDate.parse("2026-12-01"),
                List.of("b", "a", "b", "  c  ")));

        // Reading back through the mapper is the closest a test can get to a restart without
        // starting a second context: the entity is reloaded from H2, not served from the session.
        Task loaded = taskService.findById(created.id());

        assertEquals("persist me", loaded.title());
        assertEquals("alice", loaded.assignee());
        assertEquals(LocalDate.parse("2026-12-01"), loaded.dueDate());
        assertEquals(List.of("b", "a", "c"), loaded.tags(), "tag order must survive the join table");
        assertEquals(created.createdAt(), loaded.createdAt());
    }

    @Test
    void enumsAreStoredByNameNotOrdinal() {
        Task created = taskService.create(new TaskService.Draft("enum check", TaskPriority.URGENT, null, null, null));

        String status = jdbc.queryForObject(
                "select status from task where id = ?", String.class, created.id());
        String priority = jdbc.queryForObject(
                "select priority from task where id = ?", String.class, created.id());

        // Ordinal storage would put "0" here. Inserting a new constant mid-enum would then
        // silently reinterpret every existing row, so this pins the column to the name.
        assertEquals("TODO", status);
        assertEquals("URGENT", priority);
    }

    @Test
    void tagsKeepTheirPositionColumn() {
        Task created = taskService.create(new TaskService.Draft(
                "ordered", null, null, null, List.of("third", "first", "second")));

        List<String> stored = jdbc.queryForList(
                "select tag from task_tags where task_id = ? order by position", String.class, created.id());

        assertEquals(List.of("third", "first", "second"), stored);
    }

    @Test
    void instantRoundTripsWithMicrosecondPrecision() {
        Task created = taskService.create(new TaskService.Draft("clock check", null, null, null, null));

        Instant loaded = taskService.findById(created.id()).createdAt();

        assertEquals(created.createdAt(), loaded);
        assertNotNull(loaded);
        assertFalse(loaded.toString().isEmpty());
    }

    @Test
    void tagsAreLoadedEagerlySoReadsWorkWithOpenInViewOff() {
        // open-in-view=false is set in application.properties. If tags were lazy, this call
        // would throw LazyInitializationException instead of returning the list.
        taskService.create(new TaskService.Draft("eager", null, null, null, List.of("x")));

        assertTrue(taskService.findAll(null).stream().allMatch(task -> task.tags() != null));
    }

    @Test
    void statusChangePersistsAndKeepsCreatedAt() {
        Task created = taskService.create(new TaskService.Draft("move", null, null, null, null));
        Task moved = taskService.updateStatus(created.id(), TaskStatus.DOING);

        Task loaded = taskService.findById(created.id());

        assertEquals(TaskStatus.DOING, loaded.status());
        assertEquals(created.createdAt(), loaded.createdAt());
        assertTrue(loaded.updatedAt().isAfter(created.updatedAt()) || loaded.updatedAt().equals(moved.updatedAt()));
    }
}