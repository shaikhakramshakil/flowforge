package com.flowforge.engine;

import com.flowforge.config.FlowForgeProperties;

import java.time.Duration;
import java.time.Instant;

/**
 * Retry policy (F5): exponential backoff with cap, and the
 * max-attempts / dead-letter decision. Pure logic, unit-testable.
 *
 * Attempts are 1-based (claim #1 is the first run). A task that fails on
 * attempt N is retried when N < maxAttempts, else dead-lettered. The next
 * attempt is scheduled initialBackoff * 2^(N-1) seconds later, capped at
 * maxBackoff: attempt1 fail -> 2s, attempt2 fail -> 4s, ... (PRD example).
 */
public class RetryPolicy {

    private final FlowForgeProperties.Retry props;

    public RetryPolicy(FlowForgeProperties.Retry props) {
        this.props = props;
    }

    public boolean shouldRetry(int failedAttempt, int maxAttempts) {
        return failedAttempt < maxAttempts;
    }

    public Duration backoffFor(int failedAttempt) {
        long ms = props.getInitialBackoff().toMillis();
        long max = props.getMaxBackoff().toMillis();
        for (int i = 1; i < failedAttempt; i++) {
            ms = Math.min(ms * 2, max);
        }
        return Duration.ofMillis(ms);
    }

    public Instant nextRetryAt(int failedAttempt) {
        return Instant.now().plus(backoffFor(failedAttempt));
    }
}