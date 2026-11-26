package com.example.taskboard;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.persistence.TaskEntity;
import com.example.taskboard.persistence.TaskRepository;
import com.example.taskboard.service.TaskConflictException;
import com.example.taskboard.service.TaskNotFoundException;
import com.example.taskboard.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the optimistic lock actually guards writes, rather than just being declared.
 *
 * <p>A {@code @Version} field that nothing checks is indistinguishable from no field at all: the
 * second writer's {@code UPDATE ... WHERE version = ?} has to match zero rows for the conflict to
 * be real. That cannot be shown from a single-threaded service call — the second read would just
 * see the newer version — so the stale entity is produced explicitly with two transactions.
 */
@SpringBootTest
class TaskOptimisticLockTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private com.example.taskboard.persistence.JpaTaskStore store;

    @Test
    void aWriteBasedOnAStaleReadIsRejectedInsteadOfOverwriting() {
        Task created = taskService.create(new TaskService.Draft("contended", null, null, null, null));

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> outer.execute(status -> {
            TaskEntity stale = taskRepository.findById(created.id()).orElseThrow();

            // Someone else commits first, bumping the row version in a separate transaction.
            other.execute(inner -> {
                jdbc.update("update task set version = version + 1, title = ? where id = ?",
                        "winner", created.id());
                return null;
            });

            // The stale entity still carries the old version, so its UPDATE matches no row.
            stale.setTitle("loser");
            taskRepository.flush();
            return null;
        }));

        assertTrue(thrown instanceof org.springframework.dao.OptimisticLockingFailureException,
                "expected an optimistic locking failure, got " + thrown.getClass().getName());
        assertEquals("winner", taskService.findById(created.id()).title(),
                "the first committer's write must survive");
    }

    @Test
    void storeTranslatesTheLockFailureIntoTheRetryableDomainError() {
        Task created = taskService.create(new TaskService.Draft("translate", null, null, null, null));
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // The planner callback runs between the store's read and its flush — exactly the window a
        // competing request occupies. Committing a version bump from inside it makes the real
        // JpaTaskStore path, not a hand-rolled repository call, take the conflict branch.
        TaskConflictException conflict = assertThrows(TaskConflictException.class,
                () -> store.mutate(created.id(), current -> {
                    other.execute(inner -> {
                        jdbc.update("update task set version = version + 1, title = ? where id = ?",
                                "winner", created.id());
                        return null;
                    });
                    return new com.example.taskboard.service.TaskStore.Plan(
                            new Task(current.id(), "loser", current.status(), current.priority(),
                                    current.assignee(), current.dueDate(), current.tags(),
                                    current.createdAt(), current.updatedAt()),
                            com.example.taskboard.model.TaskActivityType.UPDATED, "标题");
                }));

        assertEquals(created.id(), conflict.taskId());
        assertEquals("winner", taskService.findById(created.id()).title());
    }

    @Test
    void conflictSurfacesAsRetryable409Code() {
        // The mapping is what the client sees; a bare OptimisticLockingFailureException would
        // become INTERNAL_ERROR/500 through the catch-all and look like a server bug.
        var handler = new com.example.taskboard.web.ApiExceptionHandler();
        var response = handler.handleConflict(new TaskConflictException(7L));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("CONCURRENT_MODIFICATION",
                ((com.example.taskboard.web.ApiExceptionHandler.ErrorResponse) response.getBody()).code());
    }

    @Test
    void storeReportsNotFoundRatherThanConflictForAMissingRow() {
        assertThrows(TaskNotFoundException.class, () -> taskService.updateStatus(424242L, TaskStatus.DOING));
    }
}