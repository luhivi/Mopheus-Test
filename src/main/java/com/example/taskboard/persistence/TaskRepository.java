package com.example.taskboard.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Storage for tasks. No query methods beyond CRUD on purpose: filtering, sorting and statistics
 * stay in {@code TaskQuery} over the domain objects.
 *
 * <p>Porting the id tie-break and the nulls-last due-date ordering to JPQL would mean maintaining
 * a second, subtly different implementation of rules the 59 existing tests already pin down
 * (including the documented "descending reverses the tie-break too" behaviour). One
 * implementation of the ordering is worth more than a database doing it.
 */
public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    /**
     * Bulk clear for import in replace mode. The element-collection rows must go first: their
     * foreign key to {@code tasks} would otherwise reject the bulk delete.
     *
     * <p>{@code flushAutomatically} pushes any pending inserts first so the native statement sees
     * the same state Hibernate does; {@code clearAutomatically} drops the now-stale first-level
     * cache, which still holds entities whose rows this statement removed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "delete from task_tags", nativeQuery = true)
    int deleteAllTagRows();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TaskEntity")
    int deleteAllTasks();
}