package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.parse("2026-01-01");

    private final TaskService service = new TaskService(FIXED);

    private Task create(String title) {
        return service.create(new TaskService.Draft(title, null, null, null, null));
    }

    @Test
    void newTaskIsTodoNormalWithDefaults() {
        Task task = create("demo");

        assertEquals(1L, task.id());
        assertEquals("demo", task.title());
        assertEquals(TaskStatus.TODO, task.status());
        assertEquals(TaskPriority.NORMAL, task.priority());
        assertNull(task.assignee());
        assertNull(task.dueDate());
        assertEquals(List.of(), task.tags());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), task.createdAt());
        assertEquals(task.createdAt(), task.updatedAt());
    }

    @Test
    void trimsTitleAndDropsBlankAssignee() {
        Task task = service.create(new TaskService.Draft("  padded  ", null, "   ", null, null));

        assertEquals("padded", task.title());
        assertNull(task.assignee());
    }

    @Test
    void deduplicatesTagsAndKeepsFirstOrder() {
        Task task = service.create(new TaskService.Draft("tagged", null, null, null,
                List.of("b", "a", "b", "  c  ")));

        assertEquals(List.of("b", "a", "c"), task.tags());
    }

    @Test
    void findAllReturnsNewestFirstByDefault() {
        create("first");
        create("second");

        List<Task> tasks = service.findAll(null);

        assertEquals(2L, tasks.get(0).id());
        assertEquals(1L, tasks.get(1).id());
    }

    @Test
    void ascendingOrderReversesTheList() {
        create("first");
        create("second");

        TaskQuery query = new TaskQuery(null, null, null, null, null, TaskQuery.SortKey.CREATED_AT, false, TODAY);

        assertEquals(1L, service.findAll(query).get(0).id());
    }

    @Test
    void sortsByPriorityThenId() {
        service.create(new TaskService.Draft("low", TaskPriority.LOW, null, null, null));
        service.create(new TaskService.Draft("urgent", TaskPriority.URGENT, null, null, null));
        service.create(new TaskService.Draft("normal", TaskPriority.NORMAL, null, null, null));

        TaskQuery query = new TaskQuery(null, null, null, null, null, TaskQuery.SortKey.PRIORITY, false, TODAY);
        List<Task> tasks = service.findAll(query);

        // LOW(0) < NORMAL(1) < URGENT(2) by ordinal; this pins the documented sort order.
        assertEquals(List.of("low", "normal", "urgent"), tasks.stream().map(Task::title).toList());
    }

    @Test
    void sortsByDueDateWithNullsLast() {
        service.create(new TaskService.Draft("no due", null, null, null, null));
        service.create(new TaskService.Draft("later", null, null, TODAY.plusDays(5), null));
        service.create(new TaskService.Draft("sooner", null, null, TODAY.plusDays(1), null));

        TaskQuery query = new TaskQuery(null, null, null, null, null, TaskQuery.SortKey.DUE_DATE, false, TODAY);

        assertEquals(List.of("sooner", "later", "no due"),
                service.findAll(query).stream().map(Task::title).toList());
    }

    @Test
    void filtersByStatusPriorityTextAndAssignee() {
        Task docs = service.create(new TaskService.Draft("write docs", TaskPriority.HIGH, "alice",
                null, List.of("docs")));
        service.create(new TaskService.Draft("fix bug", TaskPriority.LOW, "bob",
                null, List.of("backend")));
        service.updateStatus(docs.id(), TaskStatus.DOING);

        assertEquals(1, service.findAll(new TaskQuery(List.of(TaskStatus.DOING), null, null, null, null, null, true, TODAY)).size());
        assertEquals(1, service.findAll(new TaskQuery(List.of(TaskStatus.TODO), null, null, null, null, null, true, TODAY)).size());
        assertEquals(1, service.findAll(new TaskQuery(null, List.of(TaskPriority.HIGH), null, null, null, null, true, TODAY)).size());
        assertEquals(1, service.findAll(new TaskQuery(null, null, "DOCS", null, null, null, true, TODAY)).size());
        assertEquals(1, service.findAll(new TaskQuery(null, null, "backend", null, null, null, true, TODAY)).size());
        assertEquals(1, service.findAll(new TaskQuery(null, null, null, "ALI", null, null, true, TODAY)).size());
        assertEquals(2, service.findAll(new TaskQuery(null, null, null, null, null, null, true, TODAY)).size());
    }

    @Test
    void overdueExcludesDoneTasks() {
        Task overdueOpen = service.create(new TaskService.Draft("open", null, null, TODAY.minusDays(2), null));
        Task overdueDone = service.create(new TaskService.Draft("done", null, null, TODAY.minusDays(2), null));
        service.updateStatus(overdueDone.id(), TaskStatus.DOING);
        service.updateStatus(overdueDone.id(), TaskStatus.DONE);

        assertTrue(overdueOpen.isOverdue(TODAY));
        assertFalse(service.findById(overdueDone.id()).isOverdue(TODAY));

        TaskQuery onlyOverdue = new TaskQuery(null, null, null, null, true, null, true, TODAY);
        assertEquals(1, service.findAll(onlyOverdue).size());
    }

    @Test
    void overdueFalseMeansDoNotFilterNotOnlyNotOverdue() {
        service.create(new TaskService.Draft("late", null, null, TODAY.minusDays(2), null));
        Task finished = service.create(new TaskService.Draft("finished late", null, null, TODAY.minusDays(2), null));
        service.updateStatus(finished.id(), TaskStatus.DOING);
        service.updateStatus(finished.id(), TaskStatus.DONE);
        service.create(new TaskService.Draft("on time", null, null, TODAY.plusDays(2), null));

        // `false` and an absent parameter must behave the same. Treating false as "only tasks
        // that are not overdue" would return a set nobody asked for: not-overdue plus the
        // finished-but-was-late ones.
        TaskQuery explicitFalse = new TaskQuery(null, null, null, null, false, null, true, TODAY);
        TaskQuery absent = new TaskQuery(null, null, null, null, null, null, true, TODAY);

        assertEquals(3, service.findAll(explicitFalse).size());
        assertEquals(3, service.findAll(absent).size());
        assertEquals(1, service.findAll(new TaskQuery(null, null, null, null, true, null, true, TODAY)).size());
    }

    @Test
    void statsCountsStatusesPrioritiesAndOverdue() {
        service.create(new TaskService.Draft("a", TaskPriority.URGENT, null, TODAY.minusDays(1), null));
        Task done = service.create(new TaskService.Draft("b", TaskPriority.LOW, null, TODAY, null));
        service.updateStatus(done.id(), TaskStatus.DOING);
        service.updateStatus(done.id(), TaskStatus.DONE);

        TaskStats stats = service.stats(TODAY);

        assertEquals(2, stats.total());
        assertEquals(1, stats.overdue());
        assertEquals(0, stats.dueToday());
        assertEquals(1L, stats.byStatus().get(TaskStatus.TODO));
        assertEquals(1L, stats.byStatus().get(TaskStatus.DONE));
        assertEquals(0L, stats.byStatus().get(TaskStatus.BLOCKED));
        assertEquals(1L, stats.byPriority().get(TaskPriority.URGENT));
    }

    @Test
    void dueTodayIgnoresDoneTasks() {
        Task task = service.create(new TaskService.Draft("today", null, null, TODAY, null));
        assertTrue(task.isDueToday(TODAY));

        service.updateStatus(task.id(), TaskStatus.DOING);
        service.updateStatus(task.id(), TaskStatus.DONE);

        assertFalse(service.findById(task.id()).isDueToday(TODAY));
        assertEquals(0, service.stats(TODAY).dueToday());
    }

    @Test
    void updateRefreshesUpdatedAtButKeepsCreatedAt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        TaskService service = new TaskService(clock);
        Task created = service.create(new TaskService.Draft("before", null, null, null, null));

        clock.advance(Duration.ofMinutes(30));
        Task updated = service.update(created.id(), new TaskService.Draft("after", null, null, null, null));

        assertEquals(created.createdAt(), updated.createdAt());
        assertEquals(Instant.parse("2026-01-01T00:30:00Z"), updated.updatedAt());
        assertTrue(updated.updatedAt().isAfter(created.updatedAt()));
    }

    @Test
    void updateReplacesEditableFieldsOnly() {
        Task created = service.create(new TaskService.Draft("old", TaskPriority.LOW, "alice", TODAY, List.of("x")));

        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T01:00:00Z"), ZoneOffset.UTC);
        Task updated = service.update(created.id(), new TaskService.Draft(
                "new", TaskPriority.URGENT, "bob", TODAY.plusDays(1), List.of("y")));

        assertEquals("new", updated.title());
        assertEquals(TaskPriority.URGENT, updated.priority());
        assertEquals("bob", updated.assignee());
        assertEquals(TODAY.plusDays(1), updated.dueDate());
        assertEquals(List.of("y"), updated.tags());
        assertEquals(TaskStatus.TODO, updated.status(), "PUT must not change status");
        assertEquals(created.createdAt(), updated.createdAt());
    }

    @Test
    void updateThrowsForMissingTask() {
        assertThrows(TaskNotFoundException.class, () -> service.update(42L,
                new TaskService.Draft("x", null, null, null, null)));
    }

    @Test
    void transitionTableMatchesTheDocumentedMatrix() {
        // The full 4x4 matrix, asserted cell by cell. The browser keeps its own copy of this
        // table; if this test and that copy ever disagree, the drag refusal and the server
        // answer disagree too, which is how "the card just springs back" bugs happen.
        boolean[][] expected = {
                // to:  TODO   DOING  BLOCKED DONE
                {true, true, true, false},   // from TODO
                {true, true, true, true},    // from DOING
                {true, true, true, false},   // from BLOCKED
                {false, false, false, true}, // from DONE (same-status only)
        };

        TaskStatus[] order = {TaskStatus.TODO, TaskStatus.DOING, TaskStatus.BLOCKED, TaskStatus.DONE};
        for (int from = 0; from < order.length; from++) {
            for (int to = 0; to < order.length; to++) {
                assertEquals(expected[from][to], order[from].canMoveTo(order[to]),
                        order[from] + " -> " + order[to]);
            }
        }
    }

    @Test
    void allowsEveryDocumentedTransition() {
        Task task = create("move");

        // TODO -> DOING -> BLOCKED -> DOING -> DONE follows the table without a shortcut.
        assertEquals(TaskStatus.DOING, service.updateStatus(task.id(), TaskStatus.DOING).status());
        assertEquals(TaskStatus.BLOCKED, service.updateStatus(task.id(), TaskStatus.BLOCKED).status());
        assertEquals(TaskStatus.DOING, service.updateStatus(task.id(), TaskStatus.DOING).status());
        assertEquals(TaskStatus.DONE, service.updateStatus(task.id(), TaskStatus.DONE).status());
    }

    @Test
    void todoCannotJumpStraightToDone() {
        Task task = create("skips the work");

        InvalidTaskTransitionException error = assertThrows(InvalidTaskTransitionException.class,
                () -> service.updateStatus(task.id(), TaskStatus.DONE));

        assertEquals("TODO", error.from());
        assertEquals("DONE", error.to());
        assertEquals(TaskStatus.TODO, service.findById(task.id()).status());
    }

    @Test
    void blockedCannotSkipStraightToDone() {
        Task task = create("blocked");
        service.updateStatus(task.id(), TaskStatus.BLOCKED);

        assertThrows(InvalidTaskTransitionException.class,
                () -> service.updateStatus(task.id(), TaskStatus.DONE));
        assertEquals(TaskStatus.BLOCKED, service.findById(task.id()).status());
    }

    @Test
    void newTaskIgnoresRequestedInitialStatus() {
        // Draft has no status field at all now; this pins that every task starts at TODO.
        Task task = service.create(new TaskService.Draft("forced", TaskPriority.URGENT, "alice", null, null));

        assertEquals(TaskStatus.TODO, task.status());
    }

    @Test
    void doneIsTerminal() {
        Task task = create("finish");
        service.updateStatus(task.id(), TaskStatus.DOING);
        service.updateStatus(task.id(), TaskStatus.DONE);

        InvalidTaskTransitionException error = assertThrows(InvalidTaskTransitionException.class,
                () -> service.updateStatus(task.id(), TaskStatus.TODO));

        assertEquals("DONE", error.from());
        assertEquals("TODO", error.to());
        assertEquals(TaskStatus.DONE, service.findById(task.id()).status(), "failed move must not mutate state");
    }

    @Test
    void sameStatusIsANoOpNotAnError() {
        Task task = create("stay");

        Task again = service.updateStatus(task.id(), TaskStatus.TODO);

        assertEquals(TaskStatus.TODO, again.status());
        assertEquals(task.updatedAt(), again.updatedAt());
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
