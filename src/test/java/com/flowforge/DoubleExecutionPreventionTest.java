package com.flowforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.TaskExecution;
import com.flowforge.domain.TaskExecutionRepository;
import com.flowforge.domain.TaskStatus;
import com.flowforge.domain.Workflow;
import com.flowforge.domain.WorkflowExecution;
import com.flowforge.engine.ExecutionService;
import com.flowforge.engine.TaskClaimer;
import com.flowforge.engine.WorkflowService;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Problem C: two workers racing for the same task. The claim is a single
 * UPDATE guarded by status='READY', so exactly one claim commits; the loser
 * transparently moves on (empty when nothing else is claimable).
 */
class DoubleExecutionPreventionTest extends BaseIntegrationTest {

    @Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;
    @Autowired TaskClaimer claimer;
    @Autowired TaskExecutionRepository taskExecutions;
    @Autowired ObjectMapper mapper;

    @Test
    void concurrentClaims_exactlyOneWinner() throws Exception {
        WorkflowDefinition def = mapper.readValue(
                "{\"name\":\"race\",\"tasks\":[{\"id\":\"only\",\"type\":\"HTTP\"}]}",
                WorkflowDefinition.class);
        Workflow wf = workflows.create(def);
        WorkflowExecution exec = executions.start(wf.getId());

        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<Optional<TaskExecution>>> futures = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            String worker = "racer-" + i;
            futures.add(pool.submit(() -> {
                gun.await();
                return claimer.claim(worker);
            }));
        }
        gun.countDown();
        List<TaskExecution> won = new ArrayList<>();
        for (Future<Optional<TaskExecution>> f : futures) {
            f.get().ifPresent(won::add);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS),
                "racer threads must finish before assertions");

        assertEquals(1, won.size(), "exactly one worker must win the claim");
        TaskExecution row = taskExecutions.findByExecutionIdAndTaskId(exec.getId(), "only")
                .orElseThrow();
        assertEquals(TaskStatus.RUNNING, row.getStatus());
        assertEquals(1, row.getAttempt(), "losers must not inflate the attempt counter");
        assertEquals(won.get(0).getLeaseOwner(), row.getLeaseOwner());
    }

    @Test
    void staleAckFromPreviousOwner_rejected() throws Exception {
        WorkflowDefinition def = mapper.readValue(
                "{\"name\":\"stale\",\"tasks\":[{\"id\":\"only\",\"type\":\"HTTP\"}]}",
                WorkflowDefinition.class);
        Workflow wf = workflows.create(def);
        executions.start(wf.getId());

        TaskExecution first = claimer.claim("worker-A").orElseThrow();
        // A different worker's ack (or a replayed one) must not complete it.
        assertFalse(claimer.acknowledge(first.getId(), "worker-B",
                "bogus-lease", first.getAttempt(), mapper.createObjectNode()));
        assertEquals(TaskStatus.RUNNING,
                taskExecutions.findById(first.getId()).orElseThrow().getStatus());
    }
    @Test
    void retriedAckAfterSuccess_reportsSuccess() throws Exception {
        WorkflowDefinition def = mapper.readValue(
                "{\"name\":\"retry-ack\",\"tasks\":[{\"id\":\"only\",\"type\":\"HTTP\"}]}",
                WorkflowDefinition.class);
        Workflow wf = workflows.create(def);
        executions.start(wf.getId());

        TaskExecution claimed = claimer.claim("worker-A").orElseThrow();
        var out = mapper.createObjectNode().put("ok", true);
        assertTrue(claimer.acknowledge(claimed.getId(), "worker-A",
                claimed.getLeaseOwner(), claimed.getAttempt(), out));
        // At-least-once redelivery of the same ack must still report success.
        assertTrue(claimer.acknowledge(claimed.getId(), "worker-A",
                claimed.getLeaseOwner(), claimed.getAttempt(), out));
        assertEquals(TaskStatus.SUCCESS,
                taskExecutions.findById(claimed.getId()).orElseThrow().getStatus());
    }
}