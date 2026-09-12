package com.flowforge.engine.definition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a workflow definition before it is stored or executed:
 * duplicate task ids, dangling dependsOn references, and cycles (F2).
 * Dependencies may be declared in any order (forward references allowed).
 */
public final class DefinitionValidator {

    private DefinitionValidator() {}

    public static void validate(WorkflowDefinition def) {
        if (def.getName() == null || def.getName().isBlank()) {
            throw new IllegalArgumentException("workflow name is required");
        }
        if (def.getTasks() == null || def.getTasks().isEmpty()) {
            throw new IllegalArgumentException("workflow must declare at least one task");
        }
        // Pass 1: collect ids, reject duplicates and blank ids/types.
        Set<String> ids = new HashSet<>();
        for (TaskDefinition t : def.getTasks()) {
            if (t.getId() == null || t.getId().isBlank()) {
                throw new IllegalArgumentException("every task must have an id");
            }
            if (!ids.add(t.getId())) {
                throw new IllegalArgumentException("duplicate task id: " + t.getId());
            }
            if (t.getType() == null || t.getType().isBlank()) {
                throw new IllegalArgumentException("task '" + t.getId() + "' must declare a type");
            }
        }
        // Pass 2: every dependency must name a declared task.
        for (TaskDefinition t : def.getTasks()) {
            if (t.getDependsOn() == null) continue;
            for (String dep : t.getDependsOn()) {
                if (!ids.contains(dep)) {
                    throw new IllegalArgumentException(
                        "task '" + t.getId() + "' depends on unknown task '" + dep + "'");
                }
                if (dep.equals(t.getId())) {
                    throw new IllegalArgumentException(
                        "task '" + t.getId() + "' cannot depend on itself");
                }
            }
        }
        detectCycle(def);
    }

    private static void detectCycle(WorkflowDefinition def) {
        // Kahn's algorithm over a copy of the graph; leftovers form a cycle.
        List<String> remaining = new ArrayList<>();
        for (TaskDefinition t : def.getTasks()) remaining.add(t.getId());
        while (true) {
            boolean progressed = false;
            var it = remaining.iterator();
            while (it.hasNext()) {
                String id = it.next();
                TaskDefinition t = def.task(id);
                boolean ready = true;
                if (t.getDependsOn() != null) {
                    for (String dep : t.getDependsOn()) {
                        if (remaining.contains(dep)) { ready = false; break; }
                    }
                }
                if (ready) {
                    it.remove();
                    progressed = true;
                }
            }
            if (!progressed) break;
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException("cycle detected involving tasks: " + remaining);
        }
    }

    /** Root tasks = no dependencies; they become READY at execution start. */
    public static List<TaskDefinition> roots(WorkflowDefinition def) {
        return def.getTasks().stream()
            .filter(t -> t.getDependsOn() == null || t.getDependsOn().isEmpty())
            .toList();
    }
}