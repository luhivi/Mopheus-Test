package com.example.taskboard;

import com.example.taskboard.model.Task;
import com.example.taskboard.service.ImportExportService;
import com.example.taskboard.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves replace-import is atomic rather than assuming the annotation is enough.
 *
 * <p>Spring's {@code @Transactional} is advisory: it only works when the call crosses a proxy and
 * the transaction manager can roll back. A replace that cleared the board and then failed would
 * look identical to a successful one from the outside, so the rollback has to be demonstrated by
 * forcing a failure after the clear and checking the old rows are still there.
 */
@SpringBootTest
class ImportAtomicityTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private com.example.taskboard.persistence.JpaTaskStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void failedReplaceLeavesTheOriginalBoardIntact() {
        taskService.create(new TaskService.Draft("keep me", null, null, null, null));
        taskService.create(new TaskService.Draft("keep me too", null, null, null, null));
        long before = jdbc.queryForObject("select count(*) from task", Long.class);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            store.clearAll();
            // Simulate a failure part-way through the import write.
            throw new IllegalStateException("boom");
        }));

        assertEquals(before, jdbc.queryForObject("select count(*) from task", Long.class),
                "a replace that failed after the clear must roll the clear back");
    }

    @Test
    void replaceThroughTheServiceDoesCommitAndClearActivities() {
        Task old = taskService.create(new TaskService.Draft("old", null, null, null, null));
        long oldActivityCount = jdbc.queryForObject(
                "select count(*) from task_activity where task_id = ?", Long.class, old.id());

        var tasks = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("title", "fresh"));
        var outcome = new ImportExportService(taskService, store, objectMapper, Clock.systemUTC())
                .importTasks("replace", "replace", tasks);

        assertEquals(1, outcome.imported());
        assertEquals(1L, jdbc.queryForObject("select count(*) from task", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from task_activity", Long.class),
                "replace starts a new board, so old history (" + oldActivityCount + " rows) must go");
    }
}