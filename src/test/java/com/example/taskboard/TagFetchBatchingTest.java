package com.example.taskboard;

import com.example.taskboard.model.Task;
import com.example.taskboard.service.TaskService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the tag fetch to a batch instead of N+1.
 *
 * <p>Measured with Hibernate's own statistics rather than by eyeballing logs: an EAGER element
 * collection on 600 tasks issues one statement per owner unless it is batched, and that is the
 * difference between a board that loads and one that crawls. The assertion is on "far fewer
 * statements than rows", not an exact count, so an internal query change does not fail the suite
 * for the wrong reason.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class TagFetchBatchingTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void loadingManyTasksWithTagsDoesNotIssueOneQueryPerTask() {
        int rows = 200;
        for (int i = 0; i < rows; i++) {
            taskService.create(new TaskService.Draft("n+1 " + i, null, null, null, List.of("a", "b")));
        }

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<Task> all = taskService.findAll(null);
        long queries = stats.getPrepareStatementCount();

        assertEquals(rows, all.size());
        assertTrue(all.stream().allMatch(task -> task.tags().size() == 2), "tags must be loaded, not empty");

        // 200 owners with @BatchSize(50) is ~4 tag queries plus the task query. Without batching
        // this is 201. The bound is deliberately loose (a quarter of the rows) so a change in the
        // number of batches does not fail the test, while a regression to per-row fetching does.
        assertTrue(queries < rows / 4,
                "expected batched tag loading, but issued " + queries + " statements for " + rows + " tasks");
    }
}