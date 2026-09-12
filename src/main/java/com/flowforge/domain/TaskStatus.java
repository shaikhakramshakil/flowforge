package com.flowforge.domain;

/**
 * Task state machine (TRD section 4).
 *
 * <pre>
 * PENDING -> READY -> RUNNING -> SUCCESS
 *                            \-> FAILED -> RETRY_WAIT -> READY          (retryable)
 *                            \-> FAILED -> DEAD_LETTER                   (exhausted)
 * PENDING/READY/RETRY_WAIT -> CANCELLED                                  (workflow cancel)
 * </pre>
 */
public enum TaskStatus {
    PENDING, READY, RUNNING, SUCCESS, FAILED, RETRY_WAIT, DEAD_LETTER, CANCELLED
}