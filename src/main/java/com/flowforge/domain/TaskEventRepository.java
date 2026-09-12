package com.flowforge.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {

    List<TaskEvent> findByExecutionIdOrderByCreatedAtAsc(Long executionId);
}