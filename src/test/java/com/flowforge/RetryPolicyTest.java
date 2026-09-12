package com.flowforge;

import com.flowforge.config.FlowForgeProperties;
import com.flowforge.engine.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    private static RetryPolicy policy() {
        FlowForgeProperties props = new FlowForgeProperties();
        props.getRetry().setInitialBackoff(Duration.ofSeconds(2));
        props.getRetry().setMaxBackoff(Duration.ofSeconds(60));
        return new RetryPolicy(props.getRetry());
    }

    @Test
    void backoff_doublesPerAttempt_cappedAtMax() {
        RetryPolicy p = policy();
        assertEquals(Duration.ofSeconds(2), p.backoffFor(1));   // PRD example
        assertEquals(Duration.ofSeconds(4), p.backoffFor(2));   // PRD example
        assertEquals(Duration.ofSeconds(8), p.backoffFor(3));
        assertEquals(Duration.ofSeconds(60), p.backoffFor(10)); // capped
    }

    @Test
    void shouldRetry_boundary() {
        RetryPolicy p = policy();
        assertTrue(p.shouldRetry(1, 3));
        assertTrue(p.shouldRetry(2, 3));
        assertFalse(p.shouldRetry(3, 3));
    }
}