package com.flowforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dashboard-facing aggregated stats (F9): active/failed executions, running
 * tasks, worker health, retry/DLQ counts, recent failure reasons.
 */
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final WorkflowExecutionRepository executions;
    private final TaskExecutionRepository taskExecutions;
    private final WorkerRepository workers;
    private final ObjectMapper mapper;

    public StatsController(WorkflowExecutionRepository executions,
                           TaskExecutionRepository taskExecutions,
                           WorkerRepository workers,
                           ObjectMapper mapper) {
        this.executions = executions;
        this.taskExecutions = taskExecutions;
        this.workers = workers;
        this.mapper = mapper;
    }

    @GetMapping
    public Object stats() {
        var node = mapper.createObjectNode();
        node.put("activeExecutions",
                executions.findByStatusOrderByStartedAtDesc(ExecutionStatus.RUNNING).size());
        node.put("failedExecutions",
                executions.findByStatusOrderByStartedAtDesc(ExecutionStatus.FAILED).size());
        node.put("completedExecutions",
                executions.findByStatusOrderByStartedAtDesc(ExecutionStatus.COMPLETED).size());
        node.put("runningTasks", taskExecutions.countByStatus(TaskStatus.RUNNING));
        node.put("retryWaitTasks", taskExecutions.countByStatus(TaskStatus.RETRY_WAIT));
        node.put("deadLetterTasks", taskExecutions.countByStatus(TaskStatus.DEAD_LETTER));

        var workersNode = node.putArray("workers");
        for (Worker w : workers.findAll()) {
            workersNode.addObject()
                .put("id", w.getId())
                .put("hostname", w.getHostname())
                .put("status", w.getStatus().name())
                .put("cpuCapacity", w.getCpuCapacity())
                .put("memoryCapacityMb", w.getMemoryCapacityMb())
                .put("lastHeartbeat", w.getLastHeartbeat() == null ? null : w.getLastHeartbeat().toString());
        }

        var failuresNode = node.putArray("recentFailures");
        for (TaskExecution t : taskExecutions.findTop20ByStatusOrderByCompletedAtDesc(TaskStatus.DEAD_LETTER)) {
            failuresNode.addObject()
                .put("executionId", t.getExecutionId())
                .put("taskId", t.getTaskId())
                .put("attempt", t.getAttempt())
                .put("error", t.getError());
        }
        return node;
    }
}