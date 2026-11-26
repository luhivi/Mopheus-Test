package com.example.taskboard.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    /**
     * Clears the task table and its tag rows together. Element collections are not cascaded by a
     * bulk delete on the entity, so the tag rows need their own statement; leaving them behind
     * would make a "replace" import look successful while holding stale tags.
     */
    @Modifying
    @Query(value = "delete from task_tags", nativeQuery = true)
    void deleteAllTagRows();

    @Modifying
    @Query(value = "delete from task", nativeQuery = true)
    void deleteAllTaskRows();
}