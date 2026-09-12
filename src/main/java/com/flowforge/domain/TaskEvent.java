package com.flowforge.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "task_events")
public class TaskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    /** Null for execution-level events (EXECUTION_*), which belong to no task. */
    @Column(name = "task_id")
    private String taskId;

    @Column(name = "execution_status", nullable = false)
    private String executionStatus;

    @Column(nullable = false)
    private Integer attempt = 0;

    @Column(name = "worker_id")
    private String workerId;

    @Column
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TaskEvent() {}

    public TaskEvent(Long executionId, String taskId, String executionStatus,
                     Integer attempt, String workerId, String detail) {
        this.executionId = executionId;
        this.taskId = taskId;
        this.executionStatus = executionStatus;
        this.attempt = attempt;
        this.workerId = workerId;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getExecutionId() { return executionId; }
    public String getTaskId() { return taskId; }
    public String getExecutionStatus() { return executionStatus; }
    public Integer getAttempt() { return attempt; }
    public String getWorkerId() { return workerId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}