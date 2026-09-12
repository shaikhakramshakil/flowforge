package com.flowforge.engine.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed workflow definition (F1). The raw JSON is stored verbatim on the
 * workflow row and the execution snapshot; this is the validated view used
 * by the engine.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowDefinition {

    private String name;
    private Integer version;
    private List<TaskDefinition> tasks = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public List<TaskDefinition> getTasks() { return tasks; }
    public void setTasks(List<TaskDefinition> tasks) { this.tasks = tasks; }

    public TaskDefinition task(String id) {
        return tasks.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
    }
}