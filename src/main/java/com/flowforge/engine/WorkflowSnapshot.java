package com.flowforge.engine;

import com.flowforge.domain.Workflow;

import java.util.List;

public class WorkflowSnapshot {
    private final Long id;
    private final String name;
    private final Integer version;
    private final String definition;

    public WorkflowSnapshot(Long id, String name, Integer version, String definition) {
        this.id = id; this.name = name; this.version = version; this.definition = definition;
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public Integer getVersion() { return version; }
    public String getDefinition() { return definition; }
}