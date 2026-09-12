package com.flowforge.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "workers")
public class Worker {

    @Id
    private String id;

    @Column(nullable = false)
    private String hostname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkerStatus status = WorkerStatus.HEALTHY;

    @Column(name = "cpu_capacity", nullable = false)
    private Integer cpuCapacity;

    @Column(name = "memory_capacity_mb", nullable = false)
    private Integer memoryCapacityMb;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public WorkerStatus getStatus() { return status; }
    public void setStatus(WorkerStatus status) { this.status = status; }
    public Integer getCpuCapacity() { return cpuCapacity; }
    public void setCpuCapacity(Integer cpuCapacity) { this.cpuCapacity = cpuCapacity; }
    public Integer getMemoryCapacityMb() { return memoryCapacityMb; }
    public void setMemoryCapacityMb(Integer memoryCapacityMb) { this.memoryCapacityMb = memoryCapacityMb; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public Instant getRegisteredAt() { return registeredAt; }
}