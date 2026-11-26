package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskActivityType;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.support.InMemoryTaskStore;
import com.example.taskboard.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Activity-stream rules, tested at the service layer where the suppression conditions live.
 *
 * <p>Every test drives a {@link MutableClock} rather than the real one: an assertion about
 * "updatedAt did not move" is meaningless if the wall clock supplies different values on each
 * call, and sleeping to tell them apart would make the suite slow and flaky.
 */
class TaskActivityServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTaskStore store = new InMemoryTaskStore(clock);
    private final TaskService service = new TaskService(store, clock);

    private Task create(String title) {
        return service.create(new TaskService.Draft(title, null, null, null, null));
    }

    @Test
    void createAppendsExactlyOneCreatedActivity() {
        Task task = create("born");

        List<TaskActivity> log = service.activities(task.id(), 50);

        assertEquals(1, log.size());
        assertEquals(TaskActivityType.CREATED, log.get(0).type());
        assertEquals("创建任务", log.get(0).detail());
        assertEquals(task.id(), log.get(0).taskId());
    }

    @Test
    void statusChangeIsRecordedAsFromArrowTo() {
        Task task = create("move");
        clock.advance(Duration.ofSeconds(1));
        service.updateStatus(task.id(), TaskStatus.DOING);

        TaskActivity latest = service.activities(task.id(), 50).get(0);

        assertEquals(TaskActivityType.STATUS_CHANGED, latest.type());
        assertEquals("TODO → DOING", latest.detail());
    }

    @Test
    void sameStatusPatchIsSuppressedEntirely() {
        Task task = create("stay");
        clock.advance(Duration.ofSeconds(5));

        Task again = service.updateStatus(task.id(), TaskStatus.TODO);

        // Suppression rule 1: no status change and no touched timestamp. Logging "TODO → TODO"
        // would contradict the from-arrow-to detail format and imply work happened.
        assertEquals(task.updatedAt(), again.updatedAt());
        assertEquals(1, service.activities(task.id(), 50).size());
    }

    @Test
    void putWithNoEffectiveChangeIsSuppressed() {
        Task task = service.create(new TaskService.Draft("same", TaskPriority.HIGH, "alice",
                LocalDate.parse("2026-02-01"), List.of("x")));
        clock.advance(Duration.ofSeconds(5));

        // Same content, different spelling: padding, case of the enum, duplicate tag. After
        // normalisation this is a no-op, so no UPDATED row and no new timestamp.
        Task updated = service.update(task.id(), new TaskService.Draft("  same  ", TaskPriority.HIGH,
                "  alice  ", LocalDate.parse("2026-02-01"), List.of("x", " x ")));

        assertEquals(task.updatedAt(), updated.updatedAt());
        assertEquals(List.of("x"), updated.tags());
        assertEquals(1, service.activities(task.id(), 50).size());
    }

    @Test
    void putDetailListsOnlyTheFieldsThatChanged() {
        Task task = service.create(new TaskService.Draft("title", TaskPriority.LOW, "alice",
                LocalDate.parse("2026-02-01"), List.of("x")));
        clock.advance(Duration.ofSeconds(1));

        service.update(task.id(), new TaskService.Draft("title", TaskPriority.URGENT, "alice",
                LocalDate.parse("2026-02-01"), List.of("x", "y")));

        assertEquals("优先级、标签", service.activities(task.id(), 50).get(0).detail());
    }

    @Test
    void clearingAFilledFieldCountsAsAChange() {
        Task task = service.create(new TaskService.Draft("filled", null, "alice", null, null));
        clock.advance(Duration.ofSeconds(1));

        service.update(task.id(), new TaskService.Draft("filled", null, null, null, null));

        // Strict PUT clearing the assignee is a real edit, not "nothing happened"; the old
        // "keep priority, clear the rest" behaviour made exactly this invisible.
        assertEquals("负责人", service.activities(task.id(), 50).get(0).detail());
    }

    @Test
    void allFiveLabelsAppearInAFixedOrder() {
        Task task = create("all"); // title only, so every editable field starts empty/default
        clock.advance(Duration.ofSeconds(1));

        service.update(task.id(), new TaskService.Draft("renamed", TaskPriority.URGENT, "bob",
                LocalDate.parse("2026-03-01"), List.of("t")));

        assertEquals("标题、优先级、负责人、截止日期、标签",
                service.activities(task.id(), 50).get(0).detail());
    }

    @Test
    void deleteAppendsDeletedActivityAndKeepsItAfterTheTaskIsGone() {
        Task task = create("delete me");

        service.delete(task.id());

        List<TaskActivity> log = service.activities(task.id(), 50);

        assertEquals(2, log.size());
        assertEquals(TaskActivityType.DELETED, log.get(0).type());
        assertEquals("删除任务", log.get(0).detail());
        assertThrows(TaskNotFoundException.class, () -> service.findById(task.id()));
    }

    @Test
    void activitiesAreNewestFirstWithIdAsTieBreak() {
        Task task = create("order");
        // Two changes in the same instant: only the id can order them deterministically.
        service.updateStatus(task.id(), TaskStatus.DOING);
        service.updateStatus(task.id(), TaskStatus.BLOCKED);

        List<TaskActivity> log = service.activities(task.id(), 50);

        assertEquals(List.of("DOING → BLOCKED", "TODO → DOING", "创建任务"),
                log.stream().map(TaskActivity::detail).toList());
        for (int i = 1; i < log.size(); i++) {
            assertTrue(log.get(i - 1).id() > log.get(i).id(), "id must descend with the instant tie-break");
        }
    }

    @Test
    void limitCapsTheReturnedRows() {
        Task task = create("many");
        service.updateStatus(task.id(), TaskStatus.DOING);
        service.updateStatus(task.id(), TaskStatus.DONE);

        assertEquals(2, service.activities(task.id(), 2).size());
        assertEquals(3, service.activities(task.id(), 10).size());
    }

    @Test
    void unknownTaskWithNoHistoryIs404() {
        assertThrows(TaskNotFoundException.class, () -> service.activities(999L, 50));
    }

    @Test
    void clockAdvancesAreReflectedInActivityTimestamps() {
        Task task = create("timed");
        clock.advance(Duration.ofMinutes(10));
        service.updateStatus(task.id(), TaskStatus.DOING);

        List<TaskActivity> log = service.activities(task.id(), 50);

        assertEquals(Instant.parse("2026-01-01T00:10:00Z"), log.get(0).createdAt());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), log.get(1).createdAt());
    }
}