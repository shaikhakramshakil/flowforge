package com.flowforge.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.config.FlowForgeProperties;
import com.flowforge.engine.TaskSimulator;
import com.flowforge.engine.definition.TaskDefinition;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pull-based simulated worker (F4). Runs when flowforge.worker.enabled=true.
 *
 * Design: one claim thread acquires a concurrency permit BEFORE claiming, so
 * the worker never holds more leases than its slot budget (F3). Executed
 * tasks run on a fixed pool sized to the same budget. The heartbeat sender is
 * independent of the claim loop: a killed worker stops heartbeating and its
 * leases expire server-side, which is exactly the recovery path the demo
 * exercises (F7).
 */
@Component
public class WorkerRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    private final FlowForgeProperties props;
    private final WorkerHttpClient client;
    private final TaskSimulator simulator;
    private final WorkerGovernor governor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ExecutorService pool;
    private Thread claimThread;
    private ScheduledExecutorService heartbeatLoop;

    public WorkerRunner(FlowForgeProperties props,
                        WorkerHttpClient client,
                        TaskSimulator simulator,
                        WorkerGovernor governor) {
        this.props = props;
        this.client = client;
        this.simulator = simulator;
        this.governor = governor;
    }

    @PostConstruct
    public void start() {
        if (!props.getWorker().isEnabled()) {
            return;
        }
        running.set(true);
        var w = props.getWorker();


        int slots = governor.slots();
        pool = Executors.newFixedThreadPool(slots, r -> new Thread(r, "worker-task"));
        claimThread = new Thread(this::claimLoop, "worker-claim");
        claimThread.start();
        log.info("worker claim loop started ({} concurrent slots)", slots);

        heartbeatLoop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "worker-heartbeat"));
        heartbeatLoop.scheduleAtFixedRate(() -> {
            try {
                client.heartbeat(w.getId());
            } catch (Exception e) {
                log.warn("heartbeat failed: {}", e.getMessage());
            }
        }, 1, w.getHeartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Registration blocks (with backoff) until the server answers. This must
     * never fail context startup: in the embedded demo the worker bean starts
     * before this JVM's own HTTP layer listens.
     */
    private boolean registerWithRetry() {
        var w = props.getWorker();
        while (running.get()) {
            try {
                client.register(w.getId(), w.getHostname(), w.getCpuCapacity(), w.getMemoryCapacityMb());
                log.info("worker '{}' registered with {}", w.getId(), w.getServerUrl());
                return true;
            } catch (Exception e) {
                log.info("worker registration pending (server not up yet): {}", e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

     private void claimLoop() {
        if (!registerWithRetry()) {
            return;
        }
        while (running.get()) {
             try {
                if (!governor.tryAcquire()) {
                    Thread.sleep(300); // slots full: back off, don't over-claim
                    continue;
                }
                var maybe = client.claim(props.getWorker().getId());
                if (maybe.isEmpty()) {
                    governor.release();
                    Thread.sleep(500);
                    continue;
                }
                pool.submit(() -> runOne(maybe.get()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                governor.release();
                log.warn("claim failed: {}", e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
        log.info("worker claim loop stopped");
    }

    private void runOne(WorkerHttpClient.ClaimedTask claim) {
        try {
            String workerId = props.getWorker().getId();

            TaskDefinition def = new TaskDefinition();
            def.setId(claim.taskId());
            def.setParams(claim.params());
            def.setMaxAttempts(claim.maxAttempts());

            log.info("worker executing exec={} task={} attempt={} lease={}",
                    claim.executionId(), claim.taskId(), claim.attempt(), claim.leaseOwner());
            JsonNode out = simulator.execute(claim.executionId(), claim.taskId(), def, claim.attempt());
            if (out == null) {
                // Simulated crash: abandon the lease; server-side expiry
                // reassigns the task (F7 demo).
                log.warn("worker simulated crash: exec={} task={} attempt={}",
                        claim.executionId(), claim.taskId(), claim.attempt());
                return;
            }
            client.acknowledge(workerId, claim.taskExecutionId(), claim.leaseOwner(),
                    claim.attempt(), out);
            log.info("worker acked exec={} task={} attempt={}",
                    claim.executionId(), claim.taskId(), claim.attempt());
        } catch (TaskSimulator.SimulatedFailure f) {
            log.warn("worker task failed: {}", f.getMessage());
            fail(claim, f.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail(claim, "interrupted");
        } catch (Exception e) {
            log.warn("worker exception: {}", e.getMessage());
            fail(claim, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            governor.release();
        }
    }

    private void fail(WorkerHttpClient.ClaimedTask claim, String error) {
        try {
            client.fail(props.getWorker().getId(), claim.taskExecutionId(),
                    claim.leaseOwner(), claim.attempt(), error);
        } catch (Exception e) {
            log.warn("fail call failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (heartbeatLoop != null) heartbeatLoop.shutdownNow();
        if (claimThread != null) claimThread.interrupt();
        if (pool != null) pool.shutdownNow();
    }
}