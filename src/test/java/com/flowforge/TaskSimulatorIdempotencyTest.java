package com.flowforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.engine.TaskSimulator;
import com.flowforge.engine.definition.TaskDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The simulator's idempotent worker path: the first produced output per
 * (executionId, taskId) is recorded, and a re-claimed attempt replays it
 * without re-running the effect (F6). Sleep, fail and crash knobs are all
 * skipped on replay; only genuinely new keys execute.
 */
class TaskSimulatorIdempotencyTest {

    private final TaskSimulator simulator = new TaskSimulator();
    private final ObjectMapper mapper = new ObjectMapper();

    private TaskDefinition def(String paramsJson) throws Exception {
        TaskDefinition d = new TaskDefinition();
        d.setId("pay");
        d.setType("HTTP");
        d.setParams(mapper.readTree(paramsJson));
        return d;
    }

    @Test
    void crashAfterExecute_recordsThenReplaysWithoutRerunning() throws Exception {
        TaskDefinition d = def("{\"crashAfterExecuteOnAttempt\":1,\"sleepMs\":200}");

        // Attempt 1 runs the effect, records the output, then "crashes".
        assertNull(simulator.execute(11L, "pay", d, 1));
        assertEquals(1, simulator.executedCount(11L, "pay"));

        // Attempt 2 replays the stored output: no re-execution, no sleep.
        long start = System.nanoTime();
        ObjectNode replay = simulator.execute(11L, "pay", d, 2);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(replay);
        assertEquals(1, simulator.executedCount(11L, "pay"),
                "replay must not re-run the effect");
        assertEquals(1, replay.path("attempt").asInt(),
                "replayed output is the attempt-1 record, not a fresh run");
        assertEquals("executed pay on attempt 1", replay.path("note").asText());
        assertTrue(elapsedMs < 200,
                "replay must skip sleepMs, took " + elapsedMs + "ms");
    }

    @Test
    void replay_skipsFailKnob() throws Exception {
        ObjectNode first = simulator.execute(12L, "pay", def("{}"), 1);
        assertNotNull(first);

        // Same key, now with a fail knob: must replay, not throw.
        ObjectNode replay = simulator.execute(12L, "pay", def("{\"fail\":true,\"error\":\"boom\"}"), 2);
        assertEquals(first, replay);
        assertEquals(1, simulator.executedCount(12L, "pay"));
    }

    @Test
    void differentExecutions_areIndependent() throws Exception {
        TaskDefinition d = def("{}");
        simulator.execute(13L, "pay", d, 1);
        simulator.execute(14L, "pay", d, 1);
        assertEquals(1, simulator.executedCount(13L, "pay"));
        assertEquals(1, simulator.executedCount(14L, "pay"));
    }

    @Test
    void crashBeforeEffect_recordsNothing() throws Exception {
        TaskDefinition d = def("{\"crashOnAttempt\":1}");
        assertNull(simulator.execute(15L, "pay", d, 1));
        assertEquals(1, simulator.executedCount(15L, "pay"));
        // No output was recorded, so the next attempt genuinely re-runs.
        assertNotNull(simulator.execute(15L, "pay", d, 2));
        assertEquals(2, simulator.executedCount(15L, "pay"));
    }
}
