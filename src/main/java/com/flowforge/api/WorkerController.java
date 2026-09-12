package com.flowforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.TaskExecution;
import com.flowforge.engine.ExecutionService;
import com.flowforge.engine.TaskClaimer;
import com.flowforge.engine.WorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Worker-facing API (F4). Workers register, heartbeat, claim READY tasks,
 * then ack or fail them. The engine's CAS + lease model guarantees a task is
 * only ever RUNNING on one worker at a time.
 */
@RestController
@RequestMapping("/workers")
public class WorkerController {

    private static final Logger log = LoggerFactory.getLogger(WorkerController.class);

    private final WorkerService workerService;
    private final TaskClaimer claimer;
    private final ExecutionService executionService;
    private final ObjectMapper mapper;

    public WorkerController(WorkerService workerService, TaskClaimer claimer,
                            ExecutionService executionService, ObjectMapper mapper) {
        this.workerService = workerService;
        this.claimer = claimer;
        this.executionService = executionService;
        this.mapper = mapper;
    }

    /** POST /workers/register — worker self-registration (F4). */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody JsonNode body) {
        String id = body.path("id").asText();
        if (id.isBlank()) {
            return ResponseEntity.badRequest().body(mapper.createObjectNode().put("error", "id is required"));
        }
        workerService.register(id,
                body.path("hostname").asText(id),
                body.path("cpuCapacity").asInt(1),
                body.path("memoryCapacityMb").asInt(1024));
        return ResponseEntity.ok(mapper.createObjectNode()
                .put("id", id)
                .put("status", "HEALTHY"));
    }

    /** POST /workers/{id}/heartbeat — keep-alive (F7 / TRD section 5). */
    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable String id) {
        workerService.heartbeat(id);
        return ResponseEntity.ok(mapper.createObjectNode().put("ok", true));
    }

    /** POST /workers/{id}/claim — claim the next READY task (pull model). */
    @PostMapping("/{id}/claim")
    public ResponseEntity<?> claim(@PathVariable String id) {
        Optional<TaskExecution> claimed = claimer.claim(id);
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        TaskExecution t = claimed.get();
        var node = mapper.createObjectNode();
        node.put("taskExecutionId", t.getId());
        node.put("executionId", t.getExecutionId());
        node.put("taskId", t.getTaskId());
        node.put("attempt", t.getAttempt());
        node.put("leaseOwner", t.getLeaseOwner());
        node.put("leaseExpiresAt", t.getLeaseExpiresAt() == null
                ? null : t.getLeaseExpiresAt().toString());
        // Task params (sleepMs/fail/crash knobs) ride along so the worker
        // needs no second round-trip to fetch the definition.
        try {
            var def = executionService.loadSnapshot(t.getExecutionId()).task(t.getTaskId());
            if (def != null) {
                if (def.getParams() != null && def.getParams().isObject()) node.set("params", def.getParams());
                if (def.getMaxAttempts() != null) node.put("maxAttempts", def.getMaxAttempts());
            }
        } catch (IllegalArgumentException e) {
            // definition lookup is best-effort; worker can still run
            log.warn("claim {} served without params: definition snapshot unavailable ({})",
                    t.getId(), e.getMessage());
        }
        return ResponseEntity.ok(node);
    }

    /** POST /workers/{id}/tasks/{taskExecutionId}/ack — mark SUCCESS. */
    @PostMapping("/{id}/tasks/{taskExecutionId}/ack")
    public ResponseEntity<?> ack(@PathVariable String id, @PathVariable Long taskExecutionId,
                                 @RequestBody(required = false) JsonNode body) {
        String lease = body == null ? "" : body.path("leaseOwner").asText("");
        int attempt = body == null ? 0 : body.path("attempt").asInt(0);
        JsonNode output = body == null ? null : body.get("output");
        if (output == null || output.isMissingNode()) {
            output = mapper.createObjectNode();
        }
        boolean ok = claimer.acknowledge(taskExecutionId, id, lease, attempt, output);
        return ok
                ? ResponseEntity.ok(mapper.createObjectNode().put("acknowledged", true))
                : ResponseEntity.status(409).body(mapper.createObjectNode()
                        .put("error", "task is no longer RUNNING under this lease (stale ack)"));
    }

    /** GET /workers — list registered workers with health (F10). */
    @GetMapping
    public ResponseEntity<?> list() {
        var arr = mapper.createArrayNode();
        for (var w : workerService.all()) {
            arr.addObject()
                .put("id", w.getId())
                .put("hostname", w.getHostname())
                .put("status", w.getStatus().name())
                .put("cpuCapacity", w.getCpuCapacity())
                .put("memoryCapacityMb", w.getMemoryCapacityMb())
                .put("lastHeartbeat", w.getLastHeartbeat() == null ? null : w.getLastHeartbeat().toString());
        }
        return ResponseEntity.ok(arr);
    }

    /** POST /workers/{id}/tasks/{taskExecutionId}/fail — report failure. */
    @PostMapping("/{id}/tasks/{taskExecutionId}/fail")
    public ResponseEntity<?> fail(@PathVariable String id, @PathVariable Long taskExecutionId,
                                  @RequestBody(required = false) JsonNode body) {
        String lease = body == null ? "" : body.path("leaseOwner").asText("");
        int attempt = body == null ? 0 : body.path("attempt").asInt(0);
        String error = body == null ? "task failed (no error message)"
                : body.path("error").asText("task failed (no error message)");
        boolean ok = claimer.fail(taskExecutionId, id, lease, attempt, error);
        return ok
                ? ResponseEntity.ok(mapper.createObjectNode().put("recorded", true))
                : ResponseEntity.status(409).body(mapper.createObjectNode()
                        .put("error", "task is no longer RUNNING under this lease (stale fail)"));
    }
}