package com.flowforge.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {

    List<WorkflowExecution> findByStatusOrderByStartedAtDesc(ExecutionStatus status);

    List<WorkflowExecution> findAllByOrderByStartedAtDesc();

    @Query("select e from WorkflowExecution e where e.id = :id")
    Optional<WorkflowExecution> findByIdWithLock(@Param("id") Long id);

    @Modifying
    @Query("update WorkflowExecution e set e.status = :status, e.completedAt = CURRENT_TIMESTAMP " +
           "where e.id = :id and e.status = 'RUNNING'")
    int terminalIfRunning(@Param("id") Long id, @Param("status") ExecutionStatus status);

    @Modifying
    @Query("update WorkflowExecution e set e.cancelRequested = true " +
           "where e.id = :id and e.status = 'RUNNING'")
    int requestCancel(@Param("id") Long id);
}