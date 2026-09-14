import type {
  EventRow,
  ExecutionDetail,
  Stats,
  TaskRow,
  WorkflowSnapshot,
} from './api';

export const DEMO_MODE: boolean = import.meta.env.VITE_DEMO === '1';

/**
 * Offline demo backend (VITE_DEMO=1 build only). A scripted engine:
 * executions advance PENDING → RUNNING → SUCCESS on every read, so the
 * dashboard feels live with no server. Execute/Cancel behave like the API.
 */

const T0 = '2026-09-12T14:30:00Z';

interface DemoTask extends TaskRow {
  detail: string | null;
}

interface DemoExec {
  id: number;
  workflowId: number;
  workflowVersion: number;
  status: string;
  tasks: DemoTask[];
  events: EventRow[];
  ticks: number;
}

const workflows: WorkflowSnapshot[] = [
  { id: 1, name: 'order-processing', version: 3, definition: {} },
  { id: 2, name: 'etl-nightly', version: 7, definition: {} },
];

function ev(executionId: number, taskId: string | null, status: string, attempt: number, workerId: string | null, detail: string | null): EventRow {
  return { at: T0, executionId, taskId, status, attempt, workerId, detail };
}

const execs = new Map<number, DemoExec>();
let nextId = 101;

execs.set(101, {
  id: 101,
  workflowId: 1,
  workflowVersion: 3,
  status: 'RUNNING',
  ticks: 0,
  tasks: [
    { taskId: 'payment', status: 'SUCCESS', attempt: 1, workerId: 'demo-worker', error: undefined, detail: null },
    { taskId: 'fraud-check', status: 'SUCCESS', attempt: 1, workerId: 'demo-worker', error: undefined, detail: null },
    { taskId: 'inventory', status: 'RUNNING', attempt: 1, workerId: 'demo-worker', error: undefined, detail: null },
    { taskId: 'shipping', status: 'PENDING', attempt: 0, detail: null },
    { taskId: 'notify', status: 'PENDING', attempt: 0, detail: null },
  ],
  events: [
    ev(101, 'payment', 'CLAIMED', 1, 'demo-worker', null),
    ev(101, 'payment', 'SUCCESS', 1, 'demo-worker', null),
    ev(101, 'fraud-check', 'CLAIMED', 1, 'demo-worker', null),
    ev(101, 'fraud-check', 'SUCCESS', 1, 'demo-worker', null),
    ev(101, 'inventory', 'CLAIMED', 1, 'demo-worker', null),
  ],
});

/** Advance every running execution one step. Called on every read. */
function tick(): void {
  for (const e of execs.values()) {
    if (e.status !== 'RUNNING') continue;
    e.ticks += 1;
    const running = e.tasks.find((t) => t.status === 'RUNNING');
    if (running) {
      running.status = 'SUCCESS';
      running.detail = null;
      e.events.push(ev(e.id, running.taskId, 'SUCCESS', running.attempt, running.workerId ?? null, null));
    }
    const next = e.tasks.find((t) => t.status === 'PENDING');
    if (next) {
      next.status = 'RUNNING';
      next.attempt = 1;
      next.workerId = 'demo-worker';
      e.events.push(ev(e.id, next.taskId, 'CLAIMED', 1, 'demo-worker', null));
    } else if (!e.tasks.some((t) => t.status === 'RUNNING' || t.status === 'PENDING')) {
      e.status = 'COMPLETED';
      e.events.push(ev(e.id, null, 'EXECUTION_COMPLETED', 0, null, 'all tasks succeeded'));
    }
  }
}

function toDetail(e: DemoExec): ExecutionDetail {
  return {
    id: e.id,
    workflowId: e.workflowId,
    workflowVersion: e.workflowVersion,
    status: e.status,
    startedAt: T0,
    completedAt: e.status === 'COMPLETED' ? T0 : null,
    tasks: e.tasks.map(({ taskId, status, attempt, workerId, error }) => ({
      taskId, status, attempt, workerId, error,
    })),
    events: e.events,
  };
}

export function getStats(): Stats {
  tick();
  let running = 0;
  let dead = 0;
  let active = 0;
  let completed = 14;
  for (const e of execs.values()) {
    if (e.status === 'RUNNING') {
      active += 1;
      running += e.tasks.filter((t) => t.status === 'RUNNING').length;
    }
    if (e.status === 'COMPLETED') completed += 1;
    dead += e.tasks.filter((t) => t.status === 'DEAD_LETTER').length;
  }
  return {
    activeExecutions: active,
    failedExecutions: 1,
    completedExecutions: completed,
    runningTasks: running,
    retryWaitTasks: 1,
    deadLetterTasks: dead,
    workers: [
      { id: 'demo-worker', hostname: 'localhost', status: 'HEALTHY', cpuCapacity: 4, memoryCapacityMb: 8192, lastHeartbeat: new Date().toISOString() },
      { id: 'edge-worker', hostname: 'edge-02', status: 'UNHEALTHY', cpuCapacity: 2, memoryCapacityMb: 4096, lastHeartbeat: new Date(Date.now() - 240000).toISOString() },
    ],
    recentFailures: [
      { executionId: 98, taskId: 'charge-card', attempt: 3, error: 'card declined (max attempts 3 exceeded)' },
    ],
  };
}

export function listWorkflows(): WorkflowSnapshot[] {
  return workflows;
}

export function getExecution(id: number): ExecutionDetail {
  tick();
  const e = execs.get(id);
  if (!e) throw new Error(`HTTP 404 — execution ${id} not found`);
  return toDetail(e);
}

export function executeWorkflow(workflowId: number): { executionId: number } {
  const wf = workflows.find((w) => w.id === workflowId);
  if (!wf) throw new Error(`HTTP 404 — workflow ${workflowId} not found`);
  nextId += 1;
  const id = nextId;
  execs.set(id, {
    id,
    workflowId: wf.id,
    workflowVersion: wf.version,
    status: 'RUNNING',
    ticks: 0,
    tasks: [
      { taskId: 'payment', status: 'RUNNING', attempt: 1, workerId: 'demo-worker', error: undefined, detail: null },
      { taskId: 'shipping', status: 'PENDING', attempt: 0, detail: null },
    ],
    events: [ev(id, 'payment', 'CLAIMED', 1, 'demo-worker', null)],
  });
  return { executionId: id };
}

export function cancelExecution(id: number): unknown {
  const e = execs.get(id);
  if (!e) throw new Error(`HTTP 404 — execution ${id} not found`);
  for (const t of e.tasks) {
    if (t.status === 'PENDING' || t.status === 'READY' || t.status === 'RETRY_WAIT') {
      t.status = 'CANCELLED';
    }
  }
  if (!e.tasks.some((t) => t.status === 'RUNNING')) {
    e.status = 'CANCELLED';
    e.events.push(ev(id, null, 'EXECUTION_CANCELLED', 0, null, 'cancel requested'));
  }
  return { executionId: id, status: 'CANCEL_REQUESTED' };
}
