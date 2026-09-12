package com.flowforge.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Durable record of a task's external side effect (F6). Keyed by
 * (execution_id, task_id); the primary key makes the "record output" step a
 * first-writer-wins insert, so a worker re-claiming a task whose side effect
 * already happened (crash between side effect and acknowledgement) sees the
 * stored output and skips the external call.
 */
@Entity
@Table(name = "task_outputs")
public class TaskOutput {

    @EmbeddedId
    private TaskOutputId id;

    @Column(nullable = false)
    private String output;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TaskOutput() {}

    public TaskOutput(TaskOutputId id, String output) {
        this.id = id;
        this.output = output;
    }

    public TaskOutputId getId() { return id; }
    public String getOutput() { return output; }
    public Instant getCreatedAt() { return createdAt; }

    @Embeddable
    public static class TaskOutputId implements java.io.Serializable {
        @Column(name = "execution_id", nullable = false)
        private Long executionId;
        @Column(name = "task_id", nullable = false)
        private String taskId;

        public TaskOutputId() {}
        public TaskOutputId(Long executionId, String taskId) {
            this.executionId = executionId;
            this.taskId = taskId;
        }
        public Long getExecutionId() { return executionId; }
        public String getTaskId() { return taskId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TaskOutputId that)) return false;
            return executionId.equals(that.executionId) && taskId.equals(that.taskId);
        }
        @Override
        public int hashCode() { return 31 * executionId.hashCode() + taskId.hashCode(); }
    }
}