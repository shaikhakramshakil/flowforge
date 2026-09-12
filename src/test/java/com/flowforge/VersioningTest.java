package com.flowforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.Workflow;
import com.flowforge.domain.WorkflowExecution;
import com.flowforge.engine.ExecutionService;
import com.flowforge.engine.WorkflowService;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F8 / Problem E: executions run the definition snapshot they started with.
 * Redeploying the workflow (new version) never mutates in-flight executions.
 */
class VersioningTest extends BaseIntegrationTest {

    @Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;
    @Autowired ObjectMapper mapper;

    @Test
    void runningExecution_usesItsOwnSnapshot() throws Exception {
        Workflow v1 = workflows.create(mapper.readValue(
                "{\"name\":\"orders\",\"tasks\":[{\"id\":\"a\",\"type\":\"HTTP\"}]}",
                WorkflowDefinition.class));
        assertEquals(1, v1.getVersion());

        WorkflowExecution exec1 = executions.start(v1.getId());
        assertEquals(1, exec1.getWorkflowVersion());

        Workflow v2 = workflows.create(mapper.readValue(
                "{\"name\":\"orders\",\"tasks\":[{\"id\":\"a\",\"type\":\"HTTP\"}," +
                "{\"id\":\"b\",\"type\":\"HTTP\",\"dependsOn\":[\"a\"]}]}",
                WorkflowDefinition.class));
        assertEquals(2, v2.getVersion());

        // exec1 still runs exactly one task; a fresh execution picks up v2.
        assertEquals(1, executions.tasks(exec1.getId()).size());
        WorkflowDefinition snap1 = executions.loadSnapshot(exec1.getId());
        assertEquals(1, snap1.getTasks().size());

        WorkflowExecution exec2 = executions.start(v2.getId());
        assertEquals(2, exec2.getWorkflowVersion());
        assertEquals(2, executions.tasks(exec2.getId()).size());
    }
}