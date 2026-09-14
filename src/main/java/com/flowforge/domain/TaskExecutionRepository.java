package com.flowforge.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskExecutionRepository extends JpaRepository<TaskExecution, Long> {

    List<TaskExecution> findByExecutionIdOrderByTaskId(Long executionId);

    Optional<TaskExecution> findByExecutionIdAndTaskId(Long executionId, String taskId);

    /** Claimable tasks of running executions, highest priority first. */
    @Query(value = """
            select * from task_executions t
             where t.status = 'READY'
               and exists (select 1 from workflow_executions e
                            where e.id = t.execution_id and e.status = 'RUNNING'
                              and e.cancel_requested = false)
             order by t.priority desc, t.id asc
             limit :limit for update skip locked""", nativeQuery = true)
    List<TaskExecution> findClaimable(@Param("limit") int limit);

    /** True when every task this task depends on is SUCCESS. */
    @Query("select case when count(d) = 0 then false else true end "
             + "from TaskExecution d where d.executionId = :executionId and d.taskId in :deps "
             + "and d.status <> com.flowforge.domain.TaskStatus.SUCCESS")
    boolean anyDepUnresolved(@Param("executionId") Long executionId, @Param("deps") List<String> deps);

    @Modifying
    @Query("update TaskExecution t set t.status = 'READY' "
           + "where t.executionId = :executionId and t.taskId = :taskId and t.status = 'PENDING'")
    int markReady(@Param("executionId") Long executionId, @Param("taskId") String taskId);

    @Query("select case when count(t) > 0 then true else false end from TaskExecution t " +
           "where t.executionId = :executionId and t.status <> com.flowforge.domain.TaskStatus.SUCCESS")
    boolean anyTaskUnresolved(@Param("executionId") Long executionId);

    @Query("select count(t) from TaskExecution t where t.executionId = :executionId " +
           "and t.status = com.flowforge.domain.TaskStatus.DEAD_LETTER")
    long countDeadLetter(@Param("executionId") Long executionId);

    long countByExecutionIdAndStatus(Long executionId, TaskStatus status);

    long countByExecutionIdAndStatusNotIn(Long executionId, List<TaskStatus> statuses);

    long countByStatus(TaskStatus status);

    List<TaskExecution> findTop20ByStatusOrderByCompletedAtDesc(TaskStatus status);

    // ---------- Atomic state transitions (TRD section 6) ----------

    /**
     * Claim a READY task. The status predicate turns the UPDATE into a
     * compare-and-swap: exactly one worker's claim commits (Problem C).
     */
    @Modifying
    @Query("""
           update TaskExecution t
              set t.status = 'RUNNING', t.workerId = :worker, t.leaseOwner = :owner,
                  t.leaseExpiresAt = :expires, t.startedAt = CURRENT_TIMESTAMP,
                  t.attempt = t.attempt + 1
            where t.id = :id and t.status = 'READY'""")
    int claimReady(@Param("id") Long id, @Param("worker") String worker,
                   @Param("owner") String owner, @Param("expires") Instant expires);

    /**
     * Mark a task SUCCESS; only the current lease owner's completion commits,
     * and only for the current attempt (Problem A+C: a stale ack from an
     * earlier attempt cannot complete a task that was already recovered and
     * re-claimed at a higher attempt).
     */
    @Modifying
    @Query("""
           update TaskExecution t
              set t.status = 'SUCCESS', t.completedAt = CURRENT_TIMESTAMP,
                  t.leaseOwner = null, t.leaseExpiresAt = null
            where t.id = :id and t.leaseOwner = :owner and t.status = 'RUNNING'
              and t.attempt = :attempt""")
    int completeRunning(@Param("id") Long id, @Param("owner") String owner,
                        @Param("attempt") Integer attempt);

    /** Record failure: park in RETRY_WAIT (with backoff deadline) or send to DEAD_LETTER. */
    @Modifying
    @Query("""
           update TaskExecution t
              set t.status = :status, t.error = :error, t.retryAt = :retryAt,
                  t.completedAt = CURRENT_TIMESTAMP, t.leaseOwner = null, t.leaseExpiresAt = null
            where t.id = :id and t.leaseOwner = :owner and t.status = 'RUNNING'
              and t.attempt = :attempt""")
    int failRunning(@Param("id") Long id, @Param("owner") String owner,
                    @Param("attempt") Integer attempt, @Param("status") TaskStatus status,
                    @Param("error") String error, @Param("retryAt") Instant retryAt);

    /** Promote a RETRY_WAIT task whose backoff has elapsed back to READY. */
    @Modifying
    @Query("""
           update TaskExecution t
              set t.status = 'READY'
            where t.id = :id and t.status = 'RETRY_WAIT' and t.retryAt <= CURRENT_TIMESTAMP""")
    int retryDue(@Param("id") Long id);

    @Query("select t from TaskExecution t where t.status = 'RETRY_WAIT' and t.retryAt <= CURRENT_TIMESTAMP")
    List<TaskExecution> findDueRetries();

    @Modifying
    @Query("""
           update TaskExecution t
              set t.status = 'CANCELLED', t.completedAt = CURRENT_TIMESTAMP,
                  t.error = :detail, t.leaseOwner = null, t.leaseExpiresAt = null
            where t.id = :id and t.status in ('PENDING','READY','RETRY_WAIT')""")
    int cancelIdle(@Param("id") Long id, @Param("detail") String detail);
    /** Bulk variant: cancel every idle task of an execution in one statement. */
    @Modifying
    @Query("""
           update TaskExecution t
              set t.status = 'CANCELLED', t.completedAt = CURRENT_TIMESTAMP,
                  t.error = :detail, t.leaseOwner = null, t.leaseExpiresAt = null
            where t.executionId = :executionId and t.status in ('PENDING','READY','RETRY_WAIT')""")
    int cancelIdleByExecution(@Param("executionId") Long executionId, @Param("detail") String detail);

    // ---------- Lease expiry / recovery ----------

    /** Release a RUNNING task whose lease expired (worker lost). */
    @Modifying
    @Query("""
           update TaskExecution t
              set t.status = 'READY', t.leaseOwner = null, t.leaseExpiresAt = null,
                  t.error = :reason
            where t.id = :id and t.leaseOwner is not null and t.leaseExpiresAt < CURRENT_TIMESTAMP
              and t.status = 'RUNNING'""")
    int releaseExpiredLease(@Param("id") Long id, @Param("reason") String reason);

    @Query("""
           select t from TaskExecution t
            where t.leaseOwner is not null and t.leaseExpiresAt < CURRENT_TIMESTAMP
              and t.status = 'RUNNING'""")
    List<TaskExecution> findExpiredLeases();
}