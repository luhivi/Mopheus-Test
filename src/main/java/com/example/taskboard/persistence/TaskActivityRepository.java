package com.example.taskboard.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskActivityRepository extends JpaRepository<TaskActivityEntity, Long> {

    /**
     * History order is pinned here, oldest last: a task may receive two changes in the same
     * microsecond (a create followed immediately by a status patch), and only the id keeps that
     * deterministic. The {@link Pageable} carries the limit, not a sort.
     */
    List<TaskActivityEntity> findByTaskIdOrderByCreatedAtDescIdDesc(long taskId, Pageable pageable);
}