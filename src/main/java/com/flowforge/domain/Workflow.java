package com.flowforge.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "workflows", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}))
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private String definition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkflowStatus status = WorkflowStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }
    public WorkflowStatus getStatus() { return status; }
    public void setStatus(WorkflowStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}