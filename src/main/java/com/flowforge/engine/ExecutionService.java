package com.flowforge.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.*;
import com.flowforge.engine.definition.DefinitionValidator;
import com.flowforge.engine.definition.TaskDefinition;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ExecutionService {

    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final TaskExecutionRepository taskExecutions;
    private final TaskEventRepository events;
    private final ObjectMapper mapper;

    public ExecutionService(WorkflowRepository workflows,
                            WorkflowExecutionRepository executions,
                            TaskExecutionRepository taskExecutions,
                            TaskEventRepository events,
                            ObjectMapper mapper) {
        this.workflows = workflows;
        this.executions = executions;
        this.taskExecutions = taskExecutions;
        this.events = events;
        this.mapper = mapper;
    }

    /**
     * Start an execution of the workflow's latest version. The definition is
     * snapshotted onto the execution row, so later redeploys cannot change
     * what this execution runs (F8, Problem E).
     */
    @Transactional
    public WorkflowExecution start(Long workflowId) {
        Workflow wf = workflows.findById(workflowId)
            .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));

        WorkflowExecution exec = new WorkflowExecution();
        exec.setWorkflowId(wf.getId());
        exec.setWorkflowVersion(wf.getVersion());
        exec.setDefinitionSnapshot(wf.getDefinition());
        exec = executions.save(exec);

        WorkflowDefinition def;
        try {
            def = mapper.readValue(wf.getDefinition(), WorkflowDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("stored definition is unparseable", e);
        }
        DefinitionValidator.validate(def);

        for (TaskDefinition t : def.getTasks()) {
            TaskExecution te = new TaskExecution();
            te.setExecutionId(exec.getId());
            te.setTaskId(t.getId());
            te.setPriority(t.getPriority());
            te.setStatus(t.getDependsOn() == null || t.getDependsOn().isEmpty()
                    ? TaskStatus.READY : TaskStatus.PENDING);
            taskExecutions.save(te);
        }
        taskExecutions.flush();
        events.save(new TaskEvent(exec.getId(), null, "EXECUTION_STARTED", 0, null,
                "workflow v" + wf.getVersion() + " start"));
        return exec;
    }

    /**
     * Callable by the scheduler. Once a task succeeds, its PENDING dependents
     * whose other dependencies are all SUCCESS become READY. The row predicate
     * status = 'PENDING' makes concurrent schedule-scan state changes safe.
     */
    @Transactional
    public boolean promoteIfReady(Long executionId, String taskId) {
        WorkflowDefinition def = loadSnapshot(executionId);
        TaskDefinition t = def.task(taskId);
        if (t == null) {
            return false;
        }
        for (TaskDefinition dep : def.getTasks()) {
            if (dep.getDependsOn() == null || dep.getDependsOn().isEmpty()) {
                continue;
            }
            if (!dep.getDependsOn().contains(taskId)) {
                continue;
            }
            TaskExecution depExec = taskExecutions
                .findByExecutionIdAndTaskId(executionId, dep.getId()).orElse(null);
            if (depExec == null || depExec.getStatus() != TaskStatus.PENDING) {
                continue;
            }
            if (!taskExecutions.anyDepUnresolved(executionId, dep.getDependsOn())) {
                taskExecutions.markReady(executionId, dep.getId());
            }
        }
        return true;
    }

    @Transactional
    public WorkflowDefinition loadSnapshot(Long executionId) {
        WorkflowExecution exec = executions.findById(executionId)
            .orElseThrow(() -> new IllegalArgumentException("execution not found: " + executionId));
        try {
            return mapper.readValue(exec.getDefinitionSnapshot(), WorkflowDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("execution snapshot is unparseable", e);
        }
    }

    public List<TaskExecution> tasks(Long executionId) {
        return taskExecutions.findByExecutionIdOrderByTaskId(executionId);
    }

    @Transactional
    public WorkflowExecution get(Long executionId) {
        return executions.findById(executionId)
            .orElseThrow(() -> new IllegalArgumentException("execution not found: " + executionId));
    }

    /**
     * Graceful cancellation: no new claims, running tasks drain, everything
     * else is cancelled; the scheduler finalizes the execution as CANCELLED
     * once all tasks have settled.
     */
    @Transactional
    public void requestCancel(Long executionId) {
        WorkflowExecution exec = get(executionId);
        if (exec.getStatus() != ExecutionStatus.RUNNING) {
            throw new IllegalArgumentException("execution is not running");
        }
        executions.requestCancel(executionId);
        for (TaskExecution t : tasks(executionId)) {
            if (t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.READY
                    || t.getStatus() == TaskStatus.RETRY_WAIT) {
                taskExecutions.cancelIdle(t.getId(), "execution cancelled");
            }
        }
    }
}