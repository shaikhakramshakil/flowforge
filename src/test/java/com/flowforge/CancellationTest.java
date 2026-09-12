package com.flowforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.*;
import com.flowforge.engine.ExecutionService;
import com.flowforge.engine.Scheduler;
import com.flowforge.engine.TaskClaimer;
import com.flowforge.engine.WorkflowService;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancellation: idle tasks go CANCELLED immediately, in-flight tasks drain,
 * no new claims are handed out, and the execution settles CANCELLED.
 */
class CancellationTest extends BaseIntegrationTest {

    @Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;
    @Autowired TaskClaimer claimer;
    @Autowired Scheduler scheduler;
    @Autowired TaskExecutionRepository taskExecutions;
    @Autowired WorkflowExecutionRepository workflowExecutions;
    @Autowired ObjectMapper mapper;

    @Test
    void cancel_drainsRunningAndCancelsIdle() throws Exception {
        Workflow wf = workflows.create(mapper.readValue(
                "{\"name\":\"cancelme\",\"tasks\":[{\"id\":\"a\",\"type\":\"HTTP\"}," +
                "{\"id\":\"b\",\"type\":\"HTTP\",\"dependsOn\":[\"a\"]}]}",
                WorkflowDefinition.class));
        WorkflowExecution exec = executions.start(wf.getId());

        TaskExecution a = claimer.claim("worker-A").orElseThrow();
        executions.requestCancel(exec.getId());

        // b (PENDING) is cancelled right away; a keeps RUNNING (drains).
        assertEquals(TaskStatus.CANCELLED,
                taskExecutions.findByExecutionIdAndTaskId(exec.getId(), "b")
                        .orElseThrow().getStatus());

        // No new claims while cancelling (a is RUNNING, b is CANCELLED).
        assertTrue(claimer.claim("worker-B").isEmpty());

        // In-flight task acks normally; the execution still ends CANCELLED.
        assertTrue(claimer.acknowledge(a.getId(), "worker-A",
                a.getLeaseOwner(), a.getAttempt(), mapper.createObjectNode()));
        scheduler.runPass();
        assertEquals(ExecutionStatus.CANCELLED,
                workflowExecutions.findById(exec.getId()).orElseThrow().getStatus());
    }
}