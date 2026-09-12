package com.flowforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.domain.ExecutionStatus;
import com.flowforge.domain.TaskEventRepository;
import com.flowforge.domain.TaskExecution;
import com.flowforge.domain.TaskExecutionRepository;
import com.flowforge.domain.TaskOutputRepository;
import com.flowforge.domain.TaskStatus;
import com.flowforge.domain.Workflow;
import com.flowforge.domain.WorkflowExecution;
import com.flowforge.domain.WorkflowExecutionRepository;
import com.flowforge.engine.ExecutionService;
import com.flowforge.engine.Scheduler;
import com.flowforge.engine.TaskClaimer;
import com.flowforge.engine.TaskSimulator;
import com.flowforge.engine.WorkflowService;
import com.flowforge.engine.definition.TaskDefinition;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for the hardening pass: the fail-path attempt guard, the
 * concurrent version-assignment retry, end-to-end exactly-once replay across
 * claim -> crash -> reclaim -> ack, and a populated /stats.recentFailures.
 */
@AutoConfigureMockMvc
class EngineHardeningTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;
    @Autowired TaskClaimer claimer;
    @Autowired Scheduler scheduler;
    @Autowired TaskSimulator simulator;
    @Autowired TaskExecutionRepository taskExecutions;
    @Autowired WorkflowExecutionRepository workflowExecutions;
    @Autowired TaskOutputRepository outputs;
    @Autowired TaskEventRepository events;
    @Autowired ObjectMapper mapper;

    private WorkflowDefinition def(String json) throws Exception {
        return mapper.readValue(json, WorkflowDefinition.class);
    }

    @Test
    void staleFailFromRecoveredAttempt_cannotLand() throws Exception {
        Workflow wf = workflows.create(def(
                "{\"name\":\"stale-fail\",\"tasks\":[{\"id\":\"only\",\"type\":\"HTTP\"}]}"));
        WorkflowExecution exec = executions.start(wf.getId());

        TaskExecution first = claimer.claim("worker-A").orElseThrow();
        Thread.sleep(700); // lease (500ms) expires
        scheduler.runPass();
        assertEquals(TaskStatus.READY,
                taskExecutions.findByExecutionIdAndTaskId(exec.getId(), "only").orElseThrow().getStatus());

        TaskExecution second = claimer.claim("worker-B").orElseThrow();
        assertEquals(2, second.getAttempt());
        long eventsBefore = events.count();

        // The dead attempt's fail carries a stale (leaseOwner, attempt) pair:
        // the CAS rejects it, no event is recorded, the task stays RUNNING.
        assertFalse(claimer.fail(first.getId(), "worker-A", first.getLeaseOwner(), 1, "stale failure"));
        TaskExecution still = taskExecutions.findById(first.getId()).orElseThrow();
        assertEquals(TaskStatus.RUNNING, still.getStatus());
        assertEquals(2, still.getAttempt());
        assertEquals(eventsBefore, events.count());

        // A later legitimate fail from the current attempt still succeeds.
        assertTrue(claimer.fail(second.getId(), "worker-B", second.getLeaseOwner(), 2, "real failure"));
        TaskExecution parked = taskExecutions.findById(second.getId()).orElseThrow();
        assertEquals(TaskStatus.RETRY_WAIT, parked.getStatus());
        assertTrue(parked.getError().contains("will retry"));
    }

    @Test
    void concurrentCreates_sameName_allSucceedWithDistinctVersions() throws Exception {
        int writers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<Workflow>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            futures.add(pool.submit(() -> {
                gun.await();
                return workflows.create(def(
                        "{\"name\":\"race-name\",\"tasks\":[{\"id\":\"a\",\"type\":\"HTTP\"}]}"));
            }));
        }
        gun.countDown();
        List<Workflow> created = new ArrayList<>();
        for (Future<Workflow> f : futures) {
            created.add(f.get());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(writers, new HashSet<>(created.stream().map(Workflow::getVersion).toList()).size(),
                "every concurrent create must win a distinct version, got " +
                        created.stream().map(Workflow::getVersion).toList());
        assertEquals(writers, created.stream().map(Workflow::getVersion).sorted().toList()
                .get(writers - 1).intValue() - created.stream().map(Workflow::getVersion).sorted().toList()
                .get(0).intValue() + 1, "versions must be contiguous");
    }

    @Test
    void crashAfterEffect_reclaimedAttemptReplaysRecordedOutput() throws Exception {
        Workflow wf = workflows.create(def("""
                {"name":"exactly-once","tasks":[
                  {"id":"pay","type":"HTTP","params":{"crashAfterExecuteOnAttempt":1,"sleepMs":50}}]}"""));
        WorkflowExecution exec = executions.start(wf.getId());
        TaskDefinition payDef = executions.loadSnapshot(exec.getId()).task("pay");

        // Attempt 1: the effect runs and is recorded, then the worker "dies".
        TaskExecution c1 = claimer.claim("worker-A").orElseThrow();
        assertEquals(1, c1.getAttempt());
        assertNull(simulator.execute(exec.getId(), "pay", payDef, c1.getAttempt()));
        assertEquals(1, simulator.executedCount(exec.getId(), "pay"));

        // Lease expires, task is recovered and re-claimed at attempt 2.
        Thread.sleep(700);
        scheduler.runPass();
        TaskExecution c2 = claimer.claim("worker-B").orElseThrow();
        assertEquals(2, c2.getAttempt());

        // Attempt 2 replays the recorded output instead of re-running payment.
        ObjectNode replayed = simulator.execute(exec.getId(), "pay", payDef, c2.getAttempt());
        assertNotNull(replayed);
        assertEquals(1, simulator.executedCount(exec.getId(), "pay"),
                "the side effect must execute exactly once across the recovery");
        assertEquals(1, replayed.path("attempt").asInt());

        assertTrue(claimer.acknowledge(c2.getId(), "worker-B",
                c2.getLeaseOwner(), 2, replayed));
        assertEquals(1, outputs.count(), "output recorded exactly once");

        scheduler.runPass();
        assertEquals(ExecutionStatus.COMPLETED,
                workflowExecutions.findById(exec.getId()).orElseThrow().getStatus());
    }

    @Test
    void failEndpoint_staleLeaseIs409() throws Exception {
        Workflow wf = workflows.create(def(
                "{\"name\":\"http-fail\",\"tasks\":[{\"id\":\"only\",\"type\":\"HTTP\"}]}"));
        executions.start(wf.getId());
        TaskExecution t = claimer.claim("worker-A").orElseThrow();

        // Stale lease -> 409, task untouched.
        mvc.perform(post("/workers/worker-A/tasks/" + t.getId() + "/fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseOwner\":\"bogus\",\"attempt\":1,\"error\":\"x\"}"))
                .andExpect(status().isConflict());
        // Missing body degrades to a stale fail (409), never 400/500.
        mvc.perform(post("/workers/worker-A/tasks/" + t.getId() + "/fail"))
                .andExpect(status().isConflict());
        assertEquals(TaskStatus.RUNNING,
                taskExecutions.findById(t.getId()).orElseThrow().getStatus());

        // Legitimate fail -> 200, parked for retry.
        mvc.perform(post("/workers/worker-A/tasks/" + t.getId() + "/fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseOwner\":\"" + t.getLeaseOwner()
                                + "\",\"attempt\":1,\"error\":\"boom\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(true));
        assertEquals(TaskStatus.RETRY_WAIT,
                taskExecutions.findById(t.getId()).orElseThrow().getStatus());
    }

    @Test
    void stats_recentFailuresListsDeadLetteredTasks() throws Exception {
        Workflow wf = workflows.create(def(
                "{\"name\":\"doomed-stats\",\"tasks\":[{\"id\":\"only\",\"type\":\"HTTP\",\"maxAttempts\":1}]}"));
        executions.start(wf.getId());

        TaskExecution t = claimer.claim("worker-A").orElseThrow();
        claimer.fail(t.getId(), "worker-A", t.getLeaseOwner(), 1, "payment declined");
        scheduler.runPass(); // execution settles FAILED

        mvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadLetterTasks").value(1))
                .andExpect(jsonPath("$.recentFailures", hasSize(1)))
                .andExpect(jsonPath("$.recentFailures[0].taskId").value("only"))
                .andExpect(jsonPath("$.recentFailures[0].error", containsString("max attempts")));
    }
}
