package com.flowforge.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.config.FlowForgeProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * Thin HTTP client for the worker's claim/ack/fail calls. Sharing one
 * RestTemplate keeps connections pooled. The worker never touches Postgres —
 * it talks HTTP to the engine only, and the claim payload carries the task's
 * params so no second definition fetch is needed.
 */
@Component
public class WorkerHttpClient {

    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public WorkerHttpClient(RestTemplate rest, ObjectMapper mapper, FlowForgeProperties props) {
        this.rest = rest;
        this.mapper = mapper;
        this.baseUrl = props.getWorker().getServerUrl();
    }

    public void register(String id, String hostname, int cpu, int memMb) {
        rest.postForEntity(baseUrl + "/workers/register",
                mapper.createObjectNode()
                    .put("id", id)
                    .put("hostname", hostname)
                    .put("cpuCapacity", cpu)
                    .put("memoryCapacityMb", memMb),
                String.class);
    }

    public void heartbeat(String id) {
        rest.postForEntity(baseUrl + "/workers/" + id + "/heartbeat", null, String.class);
    }

    /** Claim the next READY task; empty when the server has nothing to offer. */
    public Optional<ClaimedTask> claim(String workerId) {
        try {
            ResponseEntity<JsonNode> resp = rest.postForEntity(
                    baseUrl + "/workers/" + workerId + "/claim", null, JsonNode.class);
            if (resp.getBody() == null || resp.getBody().isMissingNode()
                    || resp.getBody().path("taskExecutionId").isMissingNode()) {
                return Optional.empty();
            }
            JsonNode b = resp.getBody();
            Integer maxAttempts = b.path("maxAttempts").isNull() || b.path("maxAttempts").isMissingNode()
                    ? null : b.path("maxAttempts").asInt();
            return Optional.of(new ClaimedTask(
                    b.path("taskExecutionId").asLong(),
                    b.path("executionId").asLong(),
                    b.path("taskId").asText(),
                    b.path("attempt").asInt(),
                    b.path("leaseOwner").asText(),
                    b.path("leaseExpiresAt").asText(null),
                    b.get("params"),
                    maxAttempts));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NO_CONTENT) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void acknowledge(String workerId, long taskExecutionId, String leaseOwner,
                            int attempt, JsonNode output) {
        rest.postForEntity(baseUrl + "/workers/" + workerId + "/tasks/" + taskExecutionId + "/ack",
                mapper.createObjectNode()
                    .put("leaseOwner", leaseOwner)
                    .put("attempt", attempt)
                    .set("output", output),
                String.class);
    }

    public void fail(String workerId, long taskExecutionId, String leaseOwner,
                     int attempt, String error) {
        try {
            rest.postForEntity(baseUrl + "/workers/" + workerId + "/tasks/" + taskExecutionId + "/fail",
                    mapper.createObjectNode()
                        .put("leaseOwner", leaseOwner)
                        .put("attempt", attempt)
                        .put("error", error),
                    String.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT) {
                throw e;
            }
            // Stale lease: the task was already recovered and re-claimed
            // elsewhere; our failure report is obsolete, not an error.
        }
    }

    public record ClaimedTask(long taskExecutionId, long executionId, String taskId,
                              int attempt, String leaseOwner, String leaseExpiresAt,
                              JsonNode params, Integer maxAttempts) {}
}