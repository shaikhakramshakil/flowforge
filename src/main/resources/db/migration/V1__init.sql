-- FlowForge schema v1 -------------------------------------------------------
-- All task-state transitions are atomic CAS-style UPDATEs guarded by
-- (status, lease_owner) predicates; see TRD section 6.

CREATE TABLE workflows (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    version     INT         NOT NULL,
    definition  TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (name, version)
);

CREATE TABLE workflow_executions (
    id              BIGSERIAL PRIMARY KEY,
    workflow_id     BIGINT      NOT NULL REFERENCES workflows(id),
    workflow_version INT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'RUNNING'
                    CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED')),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    cancel_requested BOOLEAN    NOT NULL DEFAULT FALSE,
    -- F8: immutable copy of the definition this execution actually runs, so
    -- redeploying the workflow never mutates in-flight executions (Problem E).
    definition_snapshot TEXT  NOT NULL
);

CREATE INDEX idx_executions_status ON workflow_executions (status);

CREATE TABLE task_executions (
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT      NOT NULL REFERENCES workflow_executions(id) ON DELETE CASCADE,
    task_id      TEXT        NOT NULL,
    priority     INT         NOT NULL DEFAULT 0,
    status       TEXT        NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','READY','RUNNING','SUCCESS','FAILED',
                                   'RETRY_WAIT','DEAD_LETTER','CANCELLED')),
    attempt      INT         NOT NULL DEFAULT 0,
    worker_id    TEXT,
    lease_owner  TEXT,
    lease_expires_at TIMESTAMPTZ,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    retry_at     TIMESTAMPTZ,
    error        TEXT,
    UNIQUE (execution_id, task_id)
);

CREATE INDEX idx_task_status_ready ON task_executions (status) WHERE status IN ('READY','RETRY_WAIT');
CREATE INDEX idx_task_lease ON task_executions (lease_expires_at) WHERE lease_owner IS NOT NULL;
CREATE INDEX idx_task_execution ON task_executions (execution_id, status);

CREATE TABLE workers (
    id                TEXT        PRIMARY KEY,
    hostname          TEXT        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'HEALTHY'
                      CHECK (status IN ('HEALTHY','UNHEALTHY','OFFLINE')),
    cpu_capacity      INT         NOT NULL,
    memory_capacity_mb INT        NOT NULL,
    last_heartbeat    TIMESTAMPTZ,
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE task_events (
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT      NOT NULL,
    task_id      TEXT,
    execution_status TEXT    NOT NULL,
    attempt      INT         NOT NULL DEFAULT 0,
    worker_id    TEXT,
    detail       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_events_execution ON task_events (execution_id, created_at);

-- F6 idempotency: durable record of a task's external side effect, keyed by
-- (execution_id, task_id). A worker that re-claims a task whose side effect is
-- already recorded (crash after side effect, before acknowledgement) skips the
-- external call and only re-acknowledges. First writer wins.
CREATE TABLE task_outputs (
    execution_id  BIGINT      NOT NULL,
    task_id       TEXT        NOT NULL,
    output        TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (execution_id, task_id)
);