package com.flowforge.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.config.FlowForgeProperties;
import com.flowforge.domain.*;
import com.flowforge.engine.definition.DefinitionValidator;
import com.flowforge.engine.definition.TaskDefinition;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim/execute/ack cycle, the worker-facing half of the engine (TRD 6).
 *
 * Each claimed task gets a fresh lease UUID bound to the row's current
 * attempt. Completion/failure are CAS-guarded by (leaseOwner, attempt), so:
 *  - exactly one claim wins (Problem C)
 *  - a worker that outlived its lease cannot ack (Problem A/D)
 *  - a stale ack from a recovered attempt cannot land (Problem B)
 */
@Service
public class TaskClaimer {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    private final TaskExecutionRepository taskExecutions;
    private final TaskOutputRepository outputs;
    private final TaskEventRepository events;
    private final ExecutionService executionService;
    private final WorkflowExecutionRepository executions;
    private final RetryPolicy retryPolicy;
    private final FlowForgeProperties props;
    private final ObjectMapper mapper;

    public TaskClaimer(TaskExecutionRepository taskExecutions,
                       TaskOutputRepository outputs,
                       TaskEventRepository events,
                       ExecutionService executionService,
                       WorkflowExecutionRepository executions,
                       RetryPolicy retryPolicy,
                       FlowForgeProperties props,
                       ObjectMapper mapper) {
        this.taskExecutions = taskExecutions;
        this.outputs = outputs;
        this.events = events;
        this.executionService = executionService;
        this.executions = executions;
        this.retryPolicy = retryPolicy;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * Claim the worker's next READY task (priority order). Returns the running
     * task with its lease, or empty if nothing is claimable right now.
     * The claiming transaction commits the CAS; the execute+ack cycle is
     * deliberately outside the transaction (Problem A: the side effect must
     * not roll back with the claim).
     */
    @Transactional
    public Optional<TaskExecution> claim(String workerId) {
        List<TaskExecution> ready = taskExecutions.findClaimable(props.getScheduler().getBatchSize());
        for (TaskExecution t : ready) {
            String lease = UUID.randomUUID().toString();
            int n = taskExecutions.claimReady(t.getId(), workerId, lease,
                    Instant.now().plus(props.getScheduler().getLeaseDuration()));
            if (n == 1) {
                // The bulk CAS bypassed the persistence context: t still shows
                // the pre-claim row (attempt N-1, no lease). Refresh so the
                // worker receives the committed lease + attempt it must ack with.
                em.refresh(t);
                events.save(new TaskEvent(t.getExecutionId(), t.getTaskId(),
                        "CLAIMED", t.getAttempt(), workerId, null));
                return Optional.of(t);
            }
            // 0 rows: another worker claimed it between the select and this
            // update (or it transitioned) — try the next one.
        }
        return Optional.empty();
    }

    /**
     * Acknowledge a completed task. If the side effect was already durably
     * recorded (worker crashed AFTER the external call but BEFORE this ack),
     * this ack only re-completes the task and releases the lease — the
     * external call is never repeated (F6 idempotency + Problem A).
     */
    @Transactional
    public boolean acknowledge(Long taskId, String workerId, String leaseOwner,
                               int attempt, JsonNode output) {
        Optional<TaskExecution> maybe = taskExecutions.findById(taskId);
        if (maybe.isEmpty()) return false;
        TaskExecution t = maybe.get();
        if (t.getLeaseOwner() == null || !t.getLeaseOwner().equals(leaseOwner)) return false;

        if (outputs.findOutput(t.getExecutionId(), t.getTaskId()).isEmpty()) {
            try {
                outputs.save(new TaskOutput(
                    new TaskOutput.TaskOutputId(t.getExecutionId(), t.getTaskId()),
                    mapper.writeValueAsString(output)));
            } catch (Exception e) {
                throw new IllegalStateException("cannot serialize task output", e);
            }
        }

        int n = taskExecutions.completeRunning(taskId, leaseOwner, attempt);
        if (n == 1) {
            events.save(new TaskEvent(t.getExecutionId(), t.getTaskId(),
                    "SUCCESS", attempt, workerId, null));
            executionService.promoteIfReady(t.getExecutionId(), t.getTaskId());
            return true;
        }
        return false;
    }

    /**
     * Report a task failure. Retryable failures park in RETRY_WAIT with a
     * backoff deadline; exhausted failures go to DEAD_LETTER (F5).
     */
    @Transactional
    public void fail(Long taskId, String workerId, String leaseOwner,
                     int attempt, String error) {
        Optional<TaskExecution> maybe = taskExecutions.findById(taskId);
        if (maybe.isEmpty()) return;
        TaskExecution t = maybe.get();
        if (t.getLeaseOwner() == null || !t.getLeaseOwner().equals(leaseOwner)) return;

        TaskDefinition def = definitionFor(t);
        int maxAttempts = Optional.ofNullable(def == null ? null : def.getMaxAttempts())
                .orElse(props.getRetry().getDefaultMaxAttempts());

        TaskStatus next;
        String detail;
        Instant retryAt = null;
        if (retryPolicy.shouldRetry(attempt, maxAttempts)) {
            next = TaskStatus.RETRY_WAIT;
            retryAt = retryPolicy.nextRetryAt(attempt);
            detail = error + " (will retry, attempt " + attempt + "/" + maxAttempts + ")";
        } else {
            next = TaskStatus.DEAD_LETTER;
            detail = error + " (max attempts " + maxAttempts + " exceeded)";
        }
        int n = taskExecutions.failRunning(taskId, leaseOwner, attempt,
                next, detail, retryAt);
        if (n == 1) {
            events.save(new TaskEvent(t.getExecutionId(), t.getTaskId(),
                    next.name(), attempt, workerId, detail));
        }
    }

    /** The failing task's definition from its execution's immutable snapshot. */
    private TaskDefinition definitionFor(TaskExecution t) {
        try {
            WorkflowDefinition def = executionService.loadSnapshot(t.getExecutionId());
            DefinitionValidator.validate(def);
            return def.task(t.getTaskId());
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public void recordEvent(Long executionId, String taskId, String status,
                            int attempt, String workerId, String detail) {
        events.save(new TaskEvent(executionId, taskId, status, attempt, workerId, detail));
    }
}