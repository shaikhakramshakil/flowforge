package com.flowforge;

import com.flowforge.engine.definition.DefinitionValidator;
import com.flowforge.engine.definition.TaskDefinition;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefinitionValidatorTest {

    private static TaskDefinition task(String id, String... deps) {
        TaskDefinition t = new TaskDefinition();
        t.setId(id);
        t.setType("HTTP");
        t.setDependsOn(deps == null ? null : List.of(deps));
        return t;
    }

    private static WorkflowDefinition def(TaskDefinition... tasks) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setName("w");
        d.setTasks(new java.util.ArrayList<>(List.of(tasks)));
        return d;
    }

    @Test
    void diamond_isValid_rootsDetected() {
        WorkflowDefinition d = def(task("a"), task("b", "a"), task("c", "a"), task("d", "b", "c"));
        assertDoesNotThrow(() -> DefinitionValidator.validate(d));
        assertEquals(List.of("a"),
                DefinitionValidator.roots(d).stream().map(TaskDefinition::getId).toList());
    }

    @Test
    void forwardReferences_allowed() {
        WorkflowDefinition d = def(task("b", "a"), task("a"));
        assertDoesNotThrow(() -> DefinitionValidator.validate(d));
    }

    @Test
    void cycle_rejected() {
        WorkflowDefinition d = def(task("a", "c"), task("b", "a"), task("c", "b"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DefinitionValidator.validate(d));
        assertTrue(e.getMessage().contains("cycle"));
    }

    @Test
    void selfDependency_rejected() {
        WorkflowDefinition d = def(task("a", "a"));
        assertThrows(IllegalArgumentException.class, () -> DefinitionValidator.validate(d));
    }

    @Test
    void duplicateIds_rejected() {
        WorkflowDefinition d = def(task("a"), task("a"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DefinitionValidator.validate(d));
        assertTrue(e.getMessage().contains("duplicate"));
    }

    @Test
    void danglingDependency_rejected() {
        WorkflowDefinition d = def(task("a", "ghost"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DefinitionValidator.validate(d));
        assertTrue(e.getMessage().contains("unknown task"));
    }

    @Test
    void emptyWorkflow_rejected() {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setName("w");
        d.setTasks(List.of());
        assertThrows(IllegalArgumentException.class, () -> DefinitionValidator.validate(d));
    }
}