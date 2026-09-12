package com.flowforge.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TaskOutputRepository extends JpaRepository<TaskOutput, TaskOutput.TaskOutputId> {

    @Query("select o.output from TaskOutput o where o.id.executionId = :executionId and o.id.taskId = :taskId")
    Optional<String> findOutput(@Param("executionId") Long executionId, @Param("taskId") String taskId);
}