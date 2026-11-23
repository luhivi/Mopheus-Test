package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final TaskService service = new TaskService(FIXED);

    @Test
    void newTaskIsTodoWithFixedTimestamp() {
        Task task = service.create("demo");

        assertEquals(1L, task.id());
        assertEquals("demo", task.title());
        assertEquals(TaskStatus.TODO, task.status());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), task.createdAt());
    }

    @Test
    void findAllReturnsNewestFirst() {
        service.create("first");
        service.create("second");

        assertEquals(2L, service.findAll().get(0).id());
        assertEquals(1L, service.findAll().get(1).id());
    }

    @Test
    void updateStatusThrowsForMissingTask() {
        assertThrows(TaskNotFoundException.class, () -> service.updateStatus(42L, TaskStatus.DONE));
    }

    @Test
    void deleteThrowsForMissingTask() {
        assertThrows(TaskNotFoundException.class, () -> service.delete(42L));
    }
}
