package com.example.taskboard;

import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.persistence.TaskEntity;
import com.example.taskboard.persistence.TaskRepository;
import com.example.taskboard.support.StoreReset;
import com.example.taskboard.web.ApiExceptionHandler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Concurrent editing is now detected instead of silently last-write-wins.
 *
 * <p>Two separate claims are pinned here, and they are kept separate on purpose. A real
 * interleaving of two HTTP requests cannot be produced deterministically inside a unit test, so
 * reproducing one and calling it coverage would be a lie: what is proven instead is (a) the
 * {@code @Version} column is actually enforced by the persistence layer, and (b) the handler turns
 * that failure into the documented 409. Together those cover the path; neither alone does.
 */
@SpringBootTest
@Import(StoreReset.ClockConfig.class)
class ConcurrentModificationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void staleWriteFailsTheVersionCheck() {
        Long id = transactionTemplate.execute(status -> {
            TaskEntity entity = TaskEntity.newTask();
            entity.setTitle("race");
            entity.setStatus(TaskStatus.TODO);
            entity.setPriority(TaskPriority.NORMAL);
            entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return taskRepository.save(entity).getId();
        });

        // A detached copy carrying the version this request read.
        TaskEntity stale = transactionTemplate.execute(status -> {
            TaskEntity loaded = taskRepository.findById(id).orElseThrow();
            entityManager.detach(loaded);
            return loaded;
        });
        assertEquals(0L, stale.getVersion());

        // The other tab commits first: the stored version has moved past ours.
        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery(
                        "update tasks set version = version + 10 where id = ?1")
                .setParameter(1, id)
                .executeUpdate());

        assertThrows(OptimisticLockingFailureException.class,
                () -> transactionTemplate.executeWithoutResult(status -> {
                    stale.setTitle("loser");
                    taskRepository.saveAndFlush(stale);
                }));
    }

    @Test
    void handlerMapsTheConflictTo409WithAStableCode() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Object> response =
                handler.handleOptimisticLock(new OptimisticLockingFailureException("stale version"));

        assertEquals(409, response.getStatusCode().value());
        ApiExceptionHandler.ErrorResponse body = (ApiExceptionHandler.ErrorResponse) response.getBody();
        assertEquals("CONCURRENT_MODIFICATION", body.code());
        // The message must not leak the entity class or the SQL Hibernate wrapped inside.
        assertEquals(Boolean.FALSE, body.message().contains("TaskEntity"));
    }
}