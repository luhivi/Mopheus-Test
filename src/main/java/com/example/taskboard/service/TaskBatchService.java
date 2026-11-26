package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a batch as independent rows rather than one unit of work.
 *
 * <p>This class exists so that <em>each</em> row is its own transaction. It deliberately has no
 * {@code @Transactional} of its own and calls {@link TaskService} through its injected reference,
 * which means Spring's proxy gives every call a fresh transaction: a row that fails rolls back
 * alone, and the rows around it stay written. Wrapping the loop in one transaction — or calling
 * these methods on {@code this} — would undo exactly the semantics the batch contract promises.
 *
 * <p>A single row failing is data, not an error: it becomes {@code ok:false} with a stable code
 * and the batch still answers 200. Only a malformed request is a 400, and that decision is made
 * before this class is reached.
 */
@Service
public class TaskBatchService {

    private static final Logger log = LoggerFactory.getLogger(TaskBatchService.class);

    private final TaskService taskService;

    public TaskBatchService(TaskService taskService) {
        this.taskService = taskService;
    }

    /** Outcome of one row: exactly one of {@code task} or {@code code} is non-null. */
    public record Item(long id, Task task, String code, String message) {

        static Item success(long id, Task task) {
            return new Item(id, task, null, null);
        }

        static Item failure(long id, String code, String message) {
            return new Item(id, null, code, message);
        }
    }

    public List<Item> updateStatus(List<Long> ids, TaskStatus target) {
        List<Item> results = new ArrayList<>(ids.size());
        for (long id : ids) {
            try {
                results.add(Item.success(id, taskService.updateStatus(id, target)));
            } catch (TaskNotFoundException ex) {
                results.add(Item.failure(id, "TASK_NOT_FOUND", ex.getMessage()));
            } catch (InvalidTaskTransitionException ex) {
                results.add(Item.failure(id, "INVALID_TRANSITION",
                        "task cannot move from " + ex.from() + " to " + ex.to()));
            } catch (OptimisticLockingFailureException ex) {
                results.add(Item.failure(id, "CONCURRENT_MODIFICATION",
                        "task " + id + " was modified concurrently"));
            } catch (RuntimeException ex) {
                log.error("Batch status update failed for task {}", id, ex);
                results.add(Item.failure(id, "INTERNAL_ERROR", "internal server error"));
            }
        }
        return results;
    }

    public List<Item> delete(List<Long> ids) {
        List<Item> results = new ArrayList<>(ids.size());
        for (long id : ids) {
            try {
                taskService.delete(id);
                results.add(Item.success(id, null));
            } catch (TaskNotFoundException ex) {
                results.add(Item.failure(id, "TASK_NOT_FOUND", ex.getMessage()));
            } catch (OptimisticLockingFailureException ex) {
                results.add(Item.failure(id, "CONCURRENT_MODIFICATION",
                        "task " + id + " was modified concurrently"));
            } catch (RuntimeException ex) {
                log.error("Batch delete failed for task {}", id, ex);
                results.add(Item.failure(id, "INTERNAL_ERROR", "internal server error"));
            }
        }
        return results;
    }
}