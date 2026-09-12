package com.flowforge.worker;

import com.flowforge.config.FlowForgeProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Concurrency governor for the worker (F3 resource constraints): a semaphore
 * sized by min(cpuCapacity, memoryCapacityMb/512MB) bounds how many tasks this
 * worker executes in parallel. Scheduler/claim ordering is server-side
 * (priority); the governor is worker-local backpressure.
 */
@Component
public class WorkerGovernor {

    private final Semaphore slots;
    private final int budget;

    public WorkerGovernor(FlowForgeProperties props) {
        int cpu = Math.max(1, props.getWorker().getCpuCapacity());
        int memSlots = Math.max(1, props.getWorker().getMemoryCapacityMb() / 512);
        this.budget = Math.min(cpu, memSlots);
        this.slots = new Semaphore(budget);
    }

    /** Total parallel-task budget (exposed for pool sizing). */
    public int slots() {
        return budget;
    }

    public boolean tryAcquire() {
        return slots.tryAcquire();
    }

    public void release() {
        slots.release();
    }
}