package com.flowforge.engine;

import com.flowforge.domain.Worker;
import com.flowforge.domain.WorkerRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@org.springframework.transaction.annotation.Transactional
public class WorkerService {

    private final WorkerRepository workers;

    public WorkerService(WorkerRepository workers) {
        this.workers = workers;
    }
    public java.util.List<Worker> all() {
        return workers.findAll();
    }

     public Worker register(String id, String hostname, int cpuCapacity, int memoryCapacityMb) {
        Worker w = workers.findById(id).orElseGet(Worker::new);
        w.setId(id);
        w.setHostname(hostname);
        w.setCpuCapacity(cpuCapacity);
        w.setMemoryCapacityMb(memoryCapacityMb);
        w.setLastHeartbeat(Instant.now());
        w.setStatus(com.flowforge.domain.WorkerStatus.HEALTHY);
        return workers.save(w);
    }

    /**
     * Heartbeat refreshes last_heartbeat, auto-registers a first-time
     * worker, and revives one the scheduler marked UNHEALTHY (Problem D:
     * a partitioned worker that returns is trusted again on fresh proof
     * of life).
     */
    public void heartbeat(String id) {
        if (workers.touchHeartbeat(id, Instant.now()) == 0) {
            register(id, id, 1, 1024);
        } else {
            workers.revive(id);
        }
    }
}