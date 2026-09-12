package com.flowforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.engine.TaskSimulator;
import com.flowforge.engine.definition.TaskDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The simulator must tolerate params that round-tripped through JSON storage:
 * an absent params object deserializes as explicit-null (NullNode), and tasks
 * without params are the common case (plain HTTP tasks).
 */
class TaskSimulatorTest {

    private final TaskSimulator simulator = new TaskSimulator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nullParams_executeNormally() throws Exception {
        TaskDefinition def = new TaskDefinition();
        def.setId("t");
        def.setType("HTTP");
        def.setParams(null);
        assertNotNull(simulator.execute(def, 1));
    }

    @Test
    void nullNodeParams_executeNormally() throws Exception {
        // What a snapshot round-trip produces for {"id":"t","type":"HTTP"}.
        TaskDefinition def = mapper.readValue(
                "{\"id\":\"t\",\"type\":\"HTTP\",\"params\":null}", TaskDefinition.class);
        assertNotNull(simulator.execute(def, 1));
    }

    @Test
    void crashKnob_returnsNull() throws Exception {
        TaskDefinition def = new TaskDefinition();
        def.setId("t");
        def.setType("HTTP");
        def.setParams(mapper.readTree("{\"crashOnAttempt\":1}"));
        assertNull(simulator.execute(def, 1));
        assertNotNull(simulator.execute(def, 2));
    }

    @Test
    void failKnob_throwsUntilAttempt() throws Exception {
        TaskDefinition def = new TaskDefinition();
        def.setId("t");
        def.setType("HTTP");
        def.setParams(mapper.readTree("{\"failForAttempt\":2}"));
        assertThrows(TaskSimulator.SimulatedFailure.class, () -> simulator.execute(def, 1));
        assertNotNull(simulator.execute(def, 2));
    }
}