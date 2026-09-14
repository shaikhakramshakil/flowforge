const BASE: string = import.meta.env.VITE_API_BASE ?? '';

export interface WorkerInfo {
  id: string;
  hostname: string;
  status: string;
  cpuCapacity: number;
  memoryCapacityMb: number;
  lastHeartbeat: string | null;
}

export interface FailureInfo {
  executionId: number;
  taskId: string;
  attempt: number;
  error: string | null;
}

export interface Stats {
  activeExecutions: number;
  failedExecutions: number;
  completedExecutions: number;
  runningTasks: number;
  retryWaitTasks: number;
  deadLetterTasks: number;
  workers: WorkerInfo[];
  recentFailures: FailureInfo[];
}

export interface WorkflowSnapshot {
  id: number;
  name: string;
  version: number;
  definition: unknown;
}

export interface TaskRow {
  taskId: string;
  status: string;
  attempt: number;
  workerId?: string;
  error?: string;
}

export interface EventRow {
  at: string;
  executionId: number;
  taskId: string | null;
  status: string;
  attempt: number;
  workerId: string | null;
  detail: string | null;
}

export interface ExecutionDetail {
  id: number;
  workflowId: number;
  workflowVersion: number;
  status: string;
  startedAt: string | null;
  completedAt: string | null;
  tasks: TaskRow[];
  events: EventRow[];
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`HTTP ${res.status} — ${await res.text().catch(() => '')}`);
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  stats: () => fetch(`${BASE}/stats`).then(json<Stats>),
  workflows: () => fetch(`${BASE}/workflows`).then(json<WorkflowSnapshot[]>),
  execution: (id: number) => fetch(`${BASE}/executions/${id}`).then(json<ExecutionDetail>),
  execute: (workflowId: number) =>
    fetch(`${BASE}/workflows/${workflowId}/execute`, { method: 'POST' }).then(
      json<{ executionId: number }>,
    ),
  cancel: (executionId: number) =>
    fetch(`${BASE}/executions/${executionId}/cancel`, { method: 'POST' }).then(
      json<unknown>,
    ),
};
