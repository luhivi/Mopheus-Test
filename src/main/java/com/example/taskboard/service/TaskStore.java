package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskActivityType;

import java.util.List;
import java.util.Optional;

/**
 * Storage port for {@link TaskService}.
 *
 * <p>The service owns every rule — normalisation, the transition table, strict PUT, activity
 * suppression — and never sees an entity. Storage contributes identity, persistence and the
 * optimistic-lock check, which is what lets the service unit tests run against an in-memory
 * implementation while production runs on JPA.
 */
public interface TaskStore {

    List<Task> findAll();

    Optional<Task> findById(long id);

    long count();

    /** Inserts a new task and appends its first activity row in one transaction. */
    Task insert(Task task, TaskActivityType activityType, String activityDetail);

    /**
     * Loads the task, lets the planner decide the next state, then saves and appends the activity
     * it returned — all inside one transaction, so a status change and its audit row cannot
     * diverge and a concurrent writer fails the optimistic-lock check instead of being overwritten.
     *
     * <p>When the planner returns the current task unchanged, nothing is written and no activity
     * row is appended.
     */
    Task mutate(long id, Planner planner);

    /** Deletes the task row and appends {@code activityType} in one transaction. */
    void delete(long id, TaskActivityType activityType, String activityDetail);

    List<TaskActivity> activities(long taskId, int limit);

    /** Clears tasks and their activities in one transaction. Used by the atomic replace import. */
    void clearAll();

    /** Writes a batch of new tasks in one transaction. Import rows get no activity row. */
    List<Task> insertAll(List<Task> tasks);

    /**
     * Clears every task and activity row, then writes {@code tasks}, in ONE transaction. Split
     * into separate calls it would not be atomic: a failure between the clear and the insert
     * would leave the board empty with no way back.
     */
    List<Task> replaceAll(List<Task> tasks);

    @FunctionalInterface
    interface Planner {
        Plan plan(Task current);
    }

    /**
     * Outcome of a mutation. {@code next.equals(current)} means "nothing changed": the store
     * writes nothing and logs nothing, which is how the two suppression rules are enforced.
     */
    record Plan(Task next, TaskActivityType activityType, String activityDetail) {

        public static Plan unchanged(Task current) {
            return new Plan(current, null, null);
        }
    }
}