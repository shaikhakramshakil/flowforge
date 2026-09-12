# FlowForge — Technical Notes

How the engine answers the five hard problems from the TRD, and where the
bodies are buried in the code.

## Concurrency model (TRD §6)

All task-state transitions are single-statement `UPDATE … WHERE <expected
state>` queries; the caller checks the affected-row count (`TaskExecutionRepository`).
One row updated = the transition committed; zero rows = someone else moved it
first. There are no read-modify-write cycles anywhere in the claim/ack path, so
concurrent workers and schedulers are safe without distributed locks.

The scheduler's periodic sweep is additionally serialized across engine
instances by a **transaction-scoped** Postgres advisory lock
(`pg_try_advisory_xact_lock` in `Scheduler.sweep`). If the holder dies
mid-pass, the transaction aborts and the lock evaporates with it — no lock
table to reconcile, no orphan lease to hunt. (A session-scoped lock would leak
on a pooled connection; the xact-scoped variant cannot.)

## Problem A — worker completes a task but crashes before acknowledgement

The claim/ack cycle separates *execution* from *commit* (`TaskClaimer`):

1. `claim` CAS-moves READY → RUNNING, stamps `leaseOwner` (a fresh UUID) and
   `leaseExpiresAt`, and bumps `attempt`. The claim commits on its own; the
   side effect that follows is deliberately **outside** any DB transaction, so
   a crash can't roll the execution back into ambiguity.
2. The worker performs the external call, then `acknowledge`s, which
   first-writer-wins inserts the result into `task_outputs` keyed by
   `(execution_id, task_id)` and CAS-moves RUNNING → SUCCESS guarded by
   `(leaseOwner, attempt)`.
3. If the worker dies between the external call and the ack, the lease
   expires, the sweep re-arms the task READY, and the next worker re-claims it
   at `attempt+1` and **sees the already-recorded output**: it skips the
   external call and only re-acknowledges. The side effect happens once (F6);
   the attempt counter still records that a recovery happened.
   The simulator implements the handler side of the same contract in-process:
   `TaskSimulator.execute` records the first output per
   `(execution_id, taskId)` and replays it on later attempts without
   re-running the effect (bounded 4096-entry map, per engine instance). The
   `task_outputs` first-writer-wins insert remains the guarantee that holds
   across instances.

Net: at-least-once task execution, exactly-once recorded side effects, and a
dead worker's late ack can never land (the lease predicate rejects it).

## Problem B — scheduler crashes while assigning a task

There is no "assigning" state to crash inside of. The engine never pre-assigns
tasks to workers: workers pull via `claim`, which is one atomic UPDATE. The
sweep itself only performs idempotent CAS transitions (READY promotion, retry
promotion, lease release, execution settlement), so a crash mid-pass leaves
nothing half-written — the next pass, on this or another instance, simply
repeats the predicates. The advisory xact lock guarantees two instances never
double-apply a pass.

## Problem C — two workers attempt the same task

The claim UPDATE matches `status = 'READY'` on the specific task row. Two
workers racing the same row serialize on the row lock; exactly one UPDATE
finds the row still READY. The loser gets zero affected rows and moves on to
the next candidate (`DoubleExecutionPreventionIT` hammers this with 8
concurrent claimants and asserts a single winner with `attempt == 1`).

## Problem D — network partition

Recovery is lease-based, not heartbeat-based. A partitioned worker holds a
lease it can no longer renew; when `leaseExpiresAt` passes, any scheduler
re-arms the task regardless of what the worker row says. Heartbeats only drive
the `HEALTHY/UNHEALTHY` dashboard signal (`Scheduler.markUnhealthyWorkers`):
an UNHEALTHY worker that returns is revived on its next heartbeat. If the
partitioned worker later reconnects and tries to ack, the lease predicate
rejects it as stale.

## Problem E — definition changes while an execution is running

`POST /workflows` never mutates a version; it appends version N+1, validated
before insert. `ExecutionService.start` copies the definition JSON verbatim
onto `workflow_executions.definition_snapshot`, and every scheduling decision
(promotion, retry limits) reads from that snapshot. Redeploys only affect
executions started afterwards (`VersioningIT`). Concurrent creates for the same name race on the
next version; the UNIQUE(name, version) loser recomputes and retries
(`WorkflowService.create`, up to 3 attempts), so parallel deploys still
land distinct contiguous versions

## Retry engine (F5)

`RetryPolicy` is pure: failure on attempt N retries iff N < maxAttempts, with
`nextRetryAt = now + min(initial · 2^(N−1), max)`. Defaults (2s, ×2, 60s cap,
3 attempts) reproduce the PRD's 2s → 4s → dead-letter example. A FAILED task is
parked in RETRY_WAIT with its deadline; the sweep promotes it when due. Past
maxAttempts it goes to DEAD_LETTER, which fails the execution (dependents stay
PENDING and are never promoted). `TaskStatus.FAILED` is never persisted —
failures land directly in RETRY_WAIT or DEAD_LETTER — so
`/stats.recentFailures` reads the latest DEAD_LETTER rows (attempt, error).

## Cancellation

`requestCancel` flags the execution (claims then exclude it via
`cancel_requested = false` in `findClaimable`) and cancels every idle task
immediately. In-flight RUNNING tasks drain — their acks still commit — and the
sweep cancels any task that became READY after the request, settling the
execution CANCELLED once nothing is left running.

## What this build deliberately omits

- **Kafka/Redis**: the pull-claim protocol over Postgres is the durable queue;
  priority ordering and leases live in the same rows as the state they guard,
  so there is no queue/DB divergence to reconcile. A push broker would slot in
  behind `TaskClaimer.claim` without touching the state machine.
- **Multi-instance schedulers** beyond the advisory-lock protocol (exercised by
  design, not by a multi-node test).
- **Preemption of RUNNING tasks on cancel** (graceful drain instead).
- **A React dashboard**: `/stats` serves everything F9 lists (active/failed
  executions, running/retry/DLQ tasks, worker health, recent failure reasons)
  for a frontend to consume.
- **`settleExecutions` is O(active executions) per pass** (one scan + two
  count queries each): fine at demo scale, an aggregate GROUP BY if this ever
  guards 10k+ live executions.
- **`task_events.execution_status` is a historical name**: it stores event
  types (CLAIMED/SUCCESS/READY/RETRY_WAIT/DEAD_LETTER/EXECUTION_*), not an
  execution state. Kept to avoid a migration.
