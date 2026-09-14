package com.flowforge.engine;

import com.flowforge.config.FlowForgeProperties;
import com.flowforge.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Central scheduler sweep. Runs on a fixed cadence in every engine instance.
 *
 * Mutual exclusion across instances uses a TRANSACTION-scoped Postgres
 * advisory lock (pg_try_advisory_xact_lock): the whole pass runs inside one
 * transaction, and if the holder crashes mid-pass the transaction aborts and
 * the lock releases automatically — no leak on pooled connections, no orphan
 * half-pass state to reconcile (Problem B). Every mutation inside the pass is
 * an idempotent CAS, so a rolled-back pass is simply redone by the next one.
 *
 * Each pass:
 *  1. marks workers silent past the timeout UNHEALTHY (dashboard/ops signal),
 *  2. promotes due RETRY_WAIT tasks back to READY (F5),
 *  3. releases RUNNING tasks whose lease expired (worker lost, F7/Problem D),
 *  4. declares executions complete/failed/cancelled once their tasks settle.
 */
@Service
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private static final long LOCK_KEY = 0x466C6F77L; // "Flow"

    private final TaskExecutionRepository taskExecutions;
    private final WorkflowExecutionRepository executions;
    private final WorkerRepository workers;
    private final TaskEventRepository events;
    private final FlowForgeProperties props;

    @PersistenceContext
    private EntityManager em;

    public Scheduler(TaskExecutionRepository taskExecutions,
                     WorkflowExecutionRepository executions,
                     WorkerRepository workers,
                     TaskEventRepository events,
                     FlowForgeProperties props) {
        this.taskExecutions = taskExecutions;
        this.executions = executions;
        this.workers = workers;
        this.events = events;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${flowforge.scheduler.scan-interval-ms:1000}")
    @Transactional
    public void sweep() {
        if (!props.getScheduler().isEnabled()) {
            return;
        }
        runPass();
    }

    /**
     * One full sweep pass in a single transaction (xact-scoped advisory lock
     * inside). Exposed for tests and ops tooling, which need determinism the
     * background cadence cannot give.
     */
    @Transactional
    public void runPass() {
        Boolean locked = (Boolean) em.createNativeQuery("select pg_try_advisory_xact_lock(:k)")
                .setParameter("k", LOCK_KEY)
                .getSingleResult();
        if (!Boolean.TRUE.equals(locked)) {
            return; // another instance owns this pass
        }
        markUnhealthyWorkers();
        promoteDueRetries();
        recoverExpiredLeases();
        settleExecutions();
    }

    private void markUnhealthyWorkers() {
        Instant cutoff = Instant.now().minus(props.getScheduler().getWorkerTimeout());
        int n = workers.markUnhealthy(cutoff);
        if (n > 0) {
            log.info("marked {} worker(s) UNHEALTHY (no heartbeat since {})", n, cutoff);
        }
    }

     /** RETRY_WAIT tasks whose backoff elapsed become READY again. */
    private void promoteDueRetries() {
        List<TaskExecution> due = taskExecutions.findDueRetries();
        for (TaskExecution t : due) {
            int n = taskExecutions.retryDue(t.getId());
            if (n == 1) {
                log.debug("retry due: exec={} task={} attempt={}", t.getExecutionId(),
                        t.getTaskId(), t.getAttempt());
                events.save(new TaskEvent(t.getExecutionId(), t.getTaskId(),
                        "READY", t.getAttempt(), null, "backoff elapsed, retry due"));
            }
        }
    }

    /**
     * RUNNING tasks whose lease expired are re-armed for execution (F7).
     * The CAS predicate (leaseOwner is not null, lease expired, status
     * RUNNING) makes this safe against both the dead worker's own lingering
     * acks and double-recovery by concurrent schedulers.
     */
    private void recoverExpiredLeases() {
        List<TaskExecution> expired = taskExecutions.findExpiredLeases();
        for (TaskExecution t : expired) {
            String worker = t.getWorkerId() == null ? "?" : t.getWorkerId();
            String reason = "lease expired on worker " + worker + "; reassigned";
            int n = taskExecutions.releaseExpiredLease(t.getId(), reason);
            if (n == 1) {
                log.info("recovered task: exec={} task={} attempt={} from worker {}",
                        t.getExecutionId(), t.getTaskId(), t.getAttempt(), worker);
                events.save(new TaskEvent(t.getExecutionId(), t.getTaskId(),
                        "READY", t.getAttempt(), worker, reason));
            }
        }
    }

    /** Terminate executions whose tasks have all settled. */
    private void settleExecutions() {
        for (WorkflowExecution e : executions.findByStatusOrderByStartedAtDesc(ExecutionStatus.RUNNING)) {
            if (e.isCancelRequested()) {
                // Drain: anything that became READY after the request (e.g. a
                // dependent promoted by a draining RUNNING task) is cancelled
                // too; only in-flight RUNNING tasks may still ack.
                taskExecutions.cancelIdleByExecution(e.getId(), "execution cancelled");
                long unfinished = taskExecutions
                        .countByExecutionIdAndStatusNotIn(e.getId(),
                                List.of(TaskStatus.SUCCESS, TaskStatus.DEAD_LETTER, TaskStatus.CANCELLED));
                if (unfinished == 0) {
                    int n = executions.terminalIfRunning(e.getId(), ExecutionStatus.CANCELLED);
                    if (n == 1) {
                        events.save(new TaskEvent(e.getId(), null, "EXECUTION_CANCELLED",
                                0, null, "cancel requested"));
                    }
                }
                continue;
            }

            long deadLetters = taskExecutions.countDeadLetter(e.getId());
            if (deadLetters > 0) {
                int n = executions.terminalIfRunning(e.getId(), ExecutionStatus.FAILED);
                if (n == 1) {
                    events.save(new TaskEvent(e.getId(), null, "EXECUTION_FAILED",
                            0, null, deadLetters + " task(s) dead-lettered"));
                }
                continue;
            }

            long unfinished = taskExecutions
                    .countByExecutionIdAndStatusNotIn(e.getId(),
                            List.of(TaskStatus.SUCCESS, TaskStatus.DEAD_LETTER, TaskStatus.CANCELLED));
            if (unfinished == 0) {
                int n = executions.terminalIfRunning(e.getId(), ExecutionStatus.COMPLETED);
                if (n == 1) {
                    events.save(new TaskEvent(e.getId(), null, "EXECUTION_COMPLETED",
                            0, null, "all tasks succeeded"));
                }
            }
        }
    }
}