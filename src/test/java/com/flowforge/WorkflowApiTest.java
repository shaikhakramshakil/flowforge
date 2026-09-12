package com.flowforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST contract (F10): workflow CRUD + versioning, execute, execution detail,
 * cancel, workers, and dashboard stats — including rejection of bad input.
 */
@AutoConfigureMockMvc
class WorkflowApiTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;

    private static final String VALID = """
            {"name":"api-orders","tasks":[
              {"id":"payment","type":"HTTP"},
              {"id":"shipping","type":"HTTP","dependsOn":["payment"]}]}""";

    @Test
    void create_versions_list_execute_detail_stats() throws Exception {
        // v1
        String v1 = mvc.perform(post("/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        long v1Id = ((Number) com.jayway.jsonpath.JsonPath.read(v1, "$.id")).longValue();

        // same name again -> v2
        mvc.perform(post("/workflows").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // list contains both versions of this workflow (db is cleaned per method)
        mvc.perform(get("/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='api-orders')]", hasSize(2)));

        // execute latest of v1's workflow row -> pins v1's definition
        String exec = mvc.perform(post("/workflows/" + v1Id + "/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        long execId = ((Number) com.jayway.jsonpath.JsonPath.read(exec, "$.executionId")).longValue();

        // detail shows both tasks, payment READY
        mvc.perform(get("/executions/" + execId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks", hasSize(2)))
                .andExpect(jsonPath("$.tasks[?(@.taskId=='payment')].status",
                        contains("READY")));

        // stats reflect the running execution
        mvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeExecutions", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.workers").isArray())
                .andExpect(jsonPath("$.recentFailures").isArray());
    }

    @Test
    void invalidDefinitions_rejected() throws Exception {
        // cycle
        mvc.perform(post("/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"tasks\":[{\"id\":\"a\",\"type\":\"HTTP\",\"dependsOn\":[\"b\"]},{\"id\":\"b\",\"type\":\"HTTP\",\"dependsOn\":[\"a\"]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("cycle")));

        // dangling dependency
        mvc.perform(post("/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"tasks\":[{\"id\":\"a\",\"type\":\"HTTP\",\"dependsOn\":[\"ghost\"]}]}"))
                .andExpect(status().isBadRequest());

        // unknown workflow id
        mvc.perform(post("/workflows/999999/execute"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void workerLifecycle_claimEmpty_cancel() throws Exception {
        mvc.perform(post("/workers/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"api-w\",\"hostname\":\"h\",\"cpuCapacity\":2,\"memoryCapacityMb\":2048}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"));

        mvc.perform(post("/workers/api-w/heartbeat"))
                .andExpect(status().isOk());

        mvc.perform(get("/workers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='api-w')].status", contains("HEALTHY")));

        // nothing READY anywhere -> 204
        mvc.perform(post("/workers/api-w/claim"))
                .andExpect(status().isNoContent());

        // execute then cancel
        String exec = mvc.perform(post("/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andReturn().getResponse().getContentAsString();
        long wfId = ((Number) com.jayway.jsonpath.JsonPath.read(exec, "$.id")).longValue();
        String started = mvc.perform(post("/workflows/" + wfId + "/execute"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long execId = ((Number) com.jayway.jsonpath.JsonPath.read(started, "$.executionId")).longValue();

        mvc.perform(post("/executions/" + execId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));
    }
}