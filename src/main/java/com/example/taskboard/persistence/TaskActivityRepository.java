package com.example.taskboard.persistence;

import com.example.taskboard.model.TaskActivity;
import com.example.taskboard.model.TaskActivityType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskActivityRepository extends JpaRepository<TaskActivityEntity, Long> {

    /**
     * Newest first, id descending as the tie-break. Two activities can share the same instant,
     * and without the id tie-break the feed could reshuffle between refreshes.
     */
    @Query("select a from TaskActivityEntity a where a.taskId = :taskId "
            + "order by a.createdAt desc, a.id desc")
    List<TaskActivityEntity> findRecent(@Param("taskId") long taskId, Pageable pageable);

    @Modifying
    @Query("delete from TaskActivityEntity")
    void deleteAllActivities();
}