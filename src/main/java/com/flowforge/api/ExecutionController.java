package com.flowforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.TaskEvent;
import com.flowforge.domain.TaskEventRepository;
import com.flowforge.domain.TaskExecution;
import com.flowforge.engine.ExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping
public class ExecutionController {

    private final ExecutionService executionService;
    private final TaskEventRepository events;
    private final ObjectMapper mapper;

    public ExecutionController(ExecutionService executionService,
                               TaskEventRepository events,
                               ObjectMapper mapper) {
        this.executionService = executionService;
        this.events = events;
        this.mapper = mapper;
    }

    /** POST /workflows/{id}/execute — start an execution (latest version). */
    @PostMapping("/workflows/{id}/execute")
    public ResponseEntity<?> execute(@PathVariable Long id) {
        try {
            var exec = executionService.start(id);
            return ResponseEntity.ok(mapper.createObjectNode()
                    .put("executionId", exec.getId())
                    .put("workflowId", exec.getWorkflowId())
                    .put("workflowVersion", exec.getWorkflowVersion())
                    .put("status", exec.getStatus().name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(mapper.createObjectNode().put("error", e.getMessage()));
        }
    }

    /** GET /executions/{id} — execution detail with task rows and event log. */
    @GetMapping("/executions/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        try {
            var exec = executionService.get(id);
            List<TaskExecution> tasks = executionService.tasks(id);
            List<TaskEvent> log = events.findByExecutionIdOrderByCreatedAtAsc(id);
            var node = mapper.createObjectNode();
            node.put("id", exec.getId());
            node.put("workflowId", exec.getWorkflowId());
            node.put("workflowVersion", exec.getWorkflowVersion());
            node.put("status", exec.getStatus().name());
            node.put("startedAt", exec.getStartedAt() == null ? null : exec.getStartedAt().toString());
            node.put("completedAt", exec.getCompletedAt() == null ? null : exec.getCompletedAt().toString());
            var tasksArr = node.putArray("tasks");
            for (TaskExecution t : tasks) {
                var tn = tasksArr.addObject();
                tn.put("taskId", t.getTaskId());
                tn.put("status", t.getStatus().name());
                tn.put("attempt", t.getAttempt());
                if (t.getWorkerId() != null) tn.put("workerId", t.getWorkerId());
                if (t.getError() != null) tn.put("error", t.getError());
            }
            var logArr = node.putArray("events");
            for (TaskEvent e : log) {
                logArr.addObject()
                    .put("at", e.getCreatedAt().toString())
                    .put("executionId", e.getExecutionId())
                    .put("taskId", e.getTaskId())
                    .put("status", e.getExecutionStatus())
                    .put("attempt", e.getAttempt())
                    .put("workerId", e.getWorkerId())
                    .put("detail", e.getDetail());
            }
            return ResponseEntity.ok(node);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(mapper.createObjectNode().put("error", e.getMessage()));
        }
    }

    /** POST /executions/{id}/cancel — request graceful cancellation. */
    @PostMapping("/executions/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        try {
            executionService.requestCancel(id);
            return ResponseEntity.ok(mapper.createObjectNode()
                    .put("executionId", id)
                    .put("status", "CANCEL_REQUESTED"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(mapper.createObjectNode().put("error", e.getMessage()));
        }
    }
}