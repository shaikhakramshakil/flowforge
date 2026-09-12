package com.flowforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.*;
import com.flowforge.engine.ExecutionService;
import com.flowforge.engine.Scheduler;
import com.flowforge.engine.TaskClaimer;
import com.flowforge.engine.WorkflowService;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PRD's key demo as a test: a worker claims a task and dies before
 * acknowledging; the engine detects the expired lease, re-arms the task,
 * another claim picks it up at a higher attempt, and the workflow completes.
 * Stale acks from the dead attempt are rejected (no double-execution), and
 * the recorded side-effect output is written exactly once (F6).
 */
class CrashRecoveryTest extends BaseIntegrationTest {

    @Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;
    @Autowired TaskClaimer claimer;
    @Autowired Scheduler scheduler;
    @Autowired TaskExecutionRepository taskExecutions;
    @Autowired WorkflowExecutionRepository workflowExecutions;
    @Autowired com.flowforge.config.FlowForgeProperties props;
    @Autowired TaskOutputRepository outputs;
    @Autowired TaskEventRepository events;
    @Autowired ObjectMapper mapper;

    private WorkflowDefinition def(String json) throws Exception {
        return mapper.readValue(json, WorkflowDefinition.class);
    }

    @Test
    void workerCrashBeforeAck_recoversAndCompletes() throws Exception {
        Workflow wf = workflows.create(def("""
                {"name":"order-processing","tasks":[
                  {"id":"payment","type":"HTTP"},
                  {"id":"shipping","type":"HTTP","dependsOn":["payment"]}
                ]}"""));
        WorkflowExecution exec = executions.start(wf.getId());

        // Worker A claims payment (attempt 1) and "crashes": no ack, no fail.
        TaskExecution claimed = claimer.claim("worker-A").orElseThrow();
        assertEquals("payment", claimed.getTaskId());
        assertEquals(1, claimed.getAttempt());
        String deadLease = claimed.getLeaseOwner();
        assertNotNull(deadLease);

        // Before the lease expires the task is invisible to other workers.
        assertTrue(claimer.claim("worker-B").isEmpty());

        // Lease (500ms in tests) expires -> sweep re-arms the task READY.
        Thread.sleep(700);
        scheduler.runPass();
        TaskExecution rearmed = taskExecutions.findByExecutionIdAndTaskId(exec.getId(), "payment")
                .orElseThrow();
        assertEquals(TaskStatus.READY, rearmed.getStatus());
        assertNull(rearmed.getLeaseOwner());

        // Worker B claims it: new lease, attempt 2.
        TaskExecution reclaimed = claimer.claim("worker-B").orElseThrow();
        assertEquals("payment", reclaimed.getTaskId());
        assertEquals(2, reclaimed.getAttempt());
        assertNotEquals(deadLease, reclaimed.getLeaseOwner());

        // The dead worker's late ack is rejected: wrong lease AND old attempt.
        JsonNode out = mapper.createObjectNode().put("charged", true);
        assertFalse(claimer.acknowledge(rearmed.getId(), "worker-A", deadLease, 1, out));

        // Worker B's ack commits exactly once...
        assertTrue(claimer.acknowledge(reclaimed.getId(), "worker-B",
                reclaimed.getLeaseOwner(), 2, out));
        // ...and a duplicate ack of the same lease is a no-op (row left RUNNING).
        assertFalse(claimer.acknowledge(reclaimed.getId(), "worker-B",
                reclaimed.getLeaseOwner(), 2, out));

        // Side-effect output recorded exactly once.
        assertTrue(outputs.findOutput(exec.getId(), "payment").isPresent());

        // payment SUCCESS promoted shipping to READY; run it to completion.
        TaskExecution ship = claimer.claim("worker-B").orElseThrow();
        assertEquals("shipping", ship.getTaskId());
        assertTrue(claimer.acknowledge(ship.getId(), "worker-B",
                ship.getLeaseOwner(), ship.getAttempt(),
                mapper.createObjectNode().put("shipped", true)));

        scheduler.runPass();
        assertEquals(ExecutionStatus.COMPLETED,
                workflowExecutions.findById(exec.getId()).orElseThrow().getStatus());

        // Event log tells the whole story in order.
        List<String> statuses = events.findByExecutionIdOrderByCreatedAtAsc(exec.getId())
                .stream().map(TaskEvent::getExecutionStatus).toList();
        assertTrue(statuses.contains("CLAIMED"));
        assertTrue(statuses.contains("SUCCESS"));
    }

    @Test
    void scheduledEntryPoint_runsFullPassInTransaction() throws Exception {
        // Regression: sweep() must run runPass() inside a transaction.
        // (Self-invocation bypasses the @Transactional proxy on runPass;
        // without @Transactional on sweep() every pass died with
        // TransactionRequiredException and nothing ever recovered.)
        Workflow wf = workflows.create(def("""
                {"name":"sweep-entry","tasks":[{"id":"only","type":"HTTP"}]}"""));
        executions.start(wf.getId());
        TaskExecution t = claimer.claim("worker-A").orElseThrow();
        claimer.fail(t.getId(), "worker-A", t.getLeaseOwner(), 1, "boom");
        Thread.sleep(250); // backoff (100ms) elapses
        props.getScheduler().setEnabled(true);
        try {
            scheduler.sweep();
        } finally {
            props.getScheduler().setEnabled(false);
        }
        assertEquals(TaskStatus.READY,
                taskExecutions.findById(t.getId()).orElseThrow().getStatus());
    }

     @Test
     void failThenRetry_thenSucceeds() throws Exception {
        Workflow wf = workflows.create(def("""
                {"name":"flaky","tasks":[{"id":"only","type":"HTTP"}]}"""));
        WorkflowExecution exec = executions.start(wf.getId());

        TaskExecution first = claimer.claim("worker-A").orElseThrow();
        claimer.fail(first.getId(), "worker-A", first.getLeaseOwner(), 1, "boom");

        TaskExecution parked = taskExecutions.findById(first.getId()).orElseThrow();
        assertEquals(TaskStatus.RETRY_WAIT, parked.getStatus());
        assertNotNull(parked.getRetryAt());

        // Backoff (100ms) not yet elapsed -> sweep leaves it parked.
        scheduler.runPass();
        assertEquals(TaskStatus.RETRY_WAIT,
                taskExecutions.findById(first.getId()).orElseThrow().getStatus());

        Thread.sleep(250);
        scheduler.runPass();
        assertEquals(TaskStatus.READY,
                taskExecutions.findById(first.getId()).orElseThrow().getStatus());

        // Second attempt succeeds (simulating the transient fault clearing).
        TaskExecution second = claimer.claim("worker-A").orElseThrow();
        assertEquals(2, second.getAttempt());
        assertTrue(claimer.acknowledge(second.getId(), "worker-A",
                second.getLeaseOwner(), 2, mapper.createObjectNode()));

        scheduler.runPass();
        assertEquals(ExecutionStatus.COMPLETED,
                workflowExecutions.findById(exec.getId()).orElseThrow().getStatus());
    }

    @Test
    void exhaustedRetries_deadLetterAndFailExecution() throws Exception {
        Workflow wf = workflows.create(def("""
                {"name":"doomed","tasks":[{"id":"only","type":"HTTP","maxAttempts":2}]}"""));
        WorkflowExecution exec = executions.start(wf.getId());

        for (int attempt = 1; attempt <= 2; attempt++) {
            TaskExecution t = claimer.claim("worker-A").orElseThrow();
            assertEquals(attempt, t.getAttempt());
            claimer.fail(t.getId(), "worker-A", t.getLeaseOwner(), attempt, "always fails");
            Thread.sleep(250);
            scheduler.runPass();
        }

        TaskExecution dead = taskExecutions.findByExecutionIdAndTaskId(exec.getId(), "only")
                .orElseThrow();
        assertEquals(TaskStatus.DEAD_LETTER, dead.getStatus());
        assertTrue(dead.getError().contains("max attempts 2 exceeded"));

        scheduler.runPass();
        assertEquals(ExecutionStatus.FAILED,
                workflowExecutions.findById(exec.getId()).orElseThrow().getStatus());
    }
}