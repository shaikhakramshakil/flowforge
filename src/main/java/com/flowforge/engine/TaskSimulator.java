package com.flowforge.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.engine.definition.TaskDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated task execution for demos and integration tests. In a real
 * deployment this is replaced by the actual HTTP/integration handler; the
 * worker/engine contract (claim -> execute -> ack|fail, leases, idempotency)
 * is the same.
 *
 * Failure/crash knobs are driven by task `params`:
 *   { "fail": true, "error": "..." }          -> fail immediately
 *   { "failForAttempt": 2 }                   -> fail while attempt < 2, then succeed
 *   { "crash": true }                         -> crash before the side effect (nothing recorded)
 *   { "crashOnAttempt": 1 }                   -> crash before the side effect, only on attempt 1
 *   { "crashAfterExecute": true }             -> run the side effect, record it, then crash before the ack
 *   { "crashAfterExecuteOnAttempt": 1 }       -> same, only on attempt 1
 *   { "sleepMs": 500 }                        -> pretend to be a long-running task
 *
 * Idempotency (F6): the worker entry point {@link #execute(long, String,
 * TaskDefinition, int)} records the FIRST produced output per
 * (executionId, taskId). A re-claimed attempt (worker crashed after the
 * effect but before the ack, lease expired) is served the stored output
 * WITHOUT re-running the effect — sleep, fail and crash knobs are all
 * skipped. This models the handler-side dedupe a real deployment must
 * provide; the ack path additionally enforces first-writer-wins in
 * {@code task_outputs}, which is the guarantee that holds across engine
 * instances. The map is bounded (oldest entries evicted past the cap) and
 * lives per engine instance, so it is demo-grade by design.
 */
public class TaskSimulator {

    private static final Logger log = LoggerFactory.getLogger(TaskSimulator.class);

    /** Demo-grade bound on recorded outputs; eviction drops whole records. */
    private static final int MAX_RECORDED = 4096;

    private final JsonNodeFactory json = JsonNodeFactory.instance;

    /** Per (execution, task): effect run count + recorded output (null until produced). */
    private static final class Record {
        final AtomicInteger runs = new AtomicInteger();
        volatile ObjectNode output;
    }

    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();

    /**
     * Raw effect, no idempotency: every call runs the knobs. Kept for unit
     * tests and ad-hoc use; the worker path below is the idempotent one.
     */
    public ObjectNode execute(TaskDefinition def, int attempt) throws InterruptedException {
        return runEffect(def, attempt);
    }

    /**
     * Idempotent worker path. Returns the recorded output when this
     * (executionId, taskId) already produced one, otherwise runs the effect
     * once and records it. Returns null only when the attempt crashed before
     * producing a side effect (or crashed after executing, via
     * crashAfterExecute, in which case the output IS recorded for replay).
     */
    public ObjectNode execute(long executionId, String taskId, TaskDefinition def, int attempt)
            throws InterruptedException {
        String key = executionId + ":" + taskId;
        if (!records.containsKey(key) && records.size() >= MAX_RECORDED) {
            evictIfNeeded();
        }
        Record rec = records.computeIfAbsent(key, k -> new Record());
        ObjectNode stored = rec.output;
        if (stored != null) {
            log.info("replaying recorded output for exec={} task={} (effect ran {} time(s), now attempt {})",
                    executionId, taskId, rec.runs.get(), attempt);
            return stored.deepCopy();
        }
        synchronized (rec) {
            stored = rec.output;
            if (stored != null) {
                return stored.deepCopy();
            }
            rec.runs.incrementAndGet();
            ObjectNode out = runEffect(def, attempt);
            if (out == null) {
                return null; // crash before the side effect: nothing recorded
            }
            rec.output = out;
            if (crashAfterExecute(def.getParams(), attempt)) {
                // Crash after the side effect but before the ack: the output
                // stays recorded, so the re-claimed attempt replays it.
                return null;
            }
            return out;
        }
    }

    /** How many times the effect actually ran for (executionId, taskId); replays don't count. */
    public int executedCount(long executionId, String taskId) {
        Record rec = records.get(executionId + ":" + taskId);
        return rec == null ? 0 : rec.runs.get();
    }

    private ObjectNode runEffect(TaskDefinition def, int attempt) throws InterruptedException {
        // params round-trips through JSON storage: absent params come back as
        // explicit null (NullNode), not Java null — accept only real objects.
        JsonNode raw = def.getParams();
        ObjectNode params = (raw != null && raw.isObject()) ? (ObjectNode) raw : json.objectNode();

        int sleepMs = params.path("sleepMs").asInt(0);
        if (sleepMs > 0) {
            Thread.sleep(Math.min(sleepMs, 30_000));
        }

        boolean crash = params.path("crash").asBoolean(false);
        int crashOnAttempt = params.path("crashOnAttempt").asInt(0);
        if (crash || (crashOnAttempt > 0 && attempt == crashOnAttempt)) {
            return null; // worker interprets null as "simulate crash, abandon the lease"
        }

        boolean fail = params.path("fail").asBoolean(false) ||
                (params.path("failForAttempt").asInt(0) > attempt);
        if (fail) {
            throw new SimulatedFailure(params.path("error").asText("task failed (simulated)"));
        }

        ObjectNode out = json.objectNode();
        out.put("type", def.getType());
        out.put("ok", true);
        out.put("attempt", attempt);
        out.put("note", "executed " + def.getId() + " on attempt " + attempt);
        return out;
    }

    private static boolean crashAfterExecute(JsonNode params, int attempt) {
        if (params == null || !params.isObject()) {
            return false;
        }
        if (params.path("crashAfterExecute").asBoolean(false)) {
            return true;
        }
        int onAttempt = params.path("crashAfterExecuteOnAttempt").asInt(0);
        return onAttempt > 0 && attempt == onAttempt;
    }

    /** Best-effort bound: drop arbitrary records when the cap is reached. */
    private void evictIfNeeded() {
        if (records.size() < MAX_RECORDED) {
            return;
        }
        Iterator<String> it = records.keySet().iterator();
        for (int i = 0; i < MAX_RECORDED / 8 && it.hasNext(); i++) {
            it.next();
            it.remove();
        }
    }

    public static class SimulatedFailure extends RuntimeException {
        public SimulatedFailure(String message) { super(message); }
    }
}
