import { useCallback, useEffect, useState } from 'react';
import { api, type ExecutionDetail, type Stats, type WorkflowSnapshot } from './api';
import './styles.css';

function Badge({ value }: { value: string }) {
  return <span className={`badge st-${value}`}>{value}</span>;
}

function Tile({ label, value, sub }: { label: string; value: number; sub: string }) {
  return (
    <div className="metric-tile">
      <div className="metric-header"><span className="metric-label">{label}</span></div>
      <div className="metric-value-wrap">
        <span className="metric-value">{value}</span>
        <span className="metric-sub">{sub}</span>
      </div>
    </div>
  );
}

export default function App() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [workflows, setWorkflows] = useState<WorkflowSnapshot[]>([]);
  const [execId, setExecId] = useState<string>('');
  const [execution, setExecution] = useState<ExecutionDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [s, w] = await Promise.all([api.stats(), api.workflows()]);
      setStats(s);
      setWorkflows(w);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to connect to engine');
    }
  }, []);

  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 5000);
    return () => clearInterval(t);
  }, [load]);

  useEffect(() => {
    if (!execId) {
      setExecution(null);
      return;
    }
    const id = Number(execId);
    if (!Number.isFinite(id)) return;
    let live = true;
    const poll = () =>
      api
        .execution(id)
        .then((d) => {
          if (live) setExecution(d);
        })
        .catch(() => {});
    void poll();
    const t = setInterval(poll, 2000);
    return () => {
      live = false;
      clearInterval(t);
    };
  }, [execId]);

  async function onExecute(workflowId: number) {
    setBusy(true);
    setNotice(null);
    try {
      const res = await api.execute(workflowId);
      setExecId(String(res.executionId));
      setNotice(`Execution ${res.executionId} started`);
      await load();
    } catch (e) {
      setNotice(e instanceof Error ? e.message : 'Execute failed');
    } finally {
      setBusy(false);
    }
  }

  async function onCancel() {
    if (!execution || !window.confirm(`Cancel execution ${execution.id}?`)) return;
    setBusy(true);
    try {
      await api.cancel(execution.id);
      setNotice(`Cancel requested for execution ${execution.id}`);
      const d = await api.execution(execution.id);
      setExecution(d);
    } catch (e) {
      setNotice(e instanceof Error ? e.message : 'Cancel failed');
    } finally {
      setBusy(false);
    }
  }

  const live = !error && !!stats;

  return (
    <div className="wrap">
      <header className="page-head">
        <div className="eyebrow"><span className="pip" /><span>Workflow execution · Monochrome</span></div>
        <h1>FlowForge</h1>
        <p className="lede">Fault-tolerant distributed workflow execution — live executions, tasks, and workers.</p>
      </header>

      <div className="status-row">
        <span className={`live-badge ${live ? 'on' : 'off'}`}>
          <span className="dot" />
          {live ? 'ENGINE ONLINE' : 'DISCONNECTED'}
        </span>
        {error && <span className="dim">{error} — retrying every 5s…</span>}
        {notice && <span className="dim">{notice}</span>}
      </div>

      {stats && (
        <div className="tiles-grid" aria-label="Engine metrics">
          <Tile label="Active executions" value={stats.activeExecutions} sub="running" />
          <Tile label="Completed" value={stats.completedExecutions} sub="finished" />
          <Tile label="Failed" value={stats.failedExecutions} sub="executions" />
          <Tile label="Running tasks" value={stats.runningTasks} sub="claimed" />
          <Tile label="Retry wait" value={stats.retryWaitTasks} sub="backing off" />
          <Tile label="Dead letter" value={stats.deadLetterTasks} sub="exhausted" />
        </div>
      )}

      <section className="card">
        <h3>Workflows</h3>
        {workflows.length === 0 ? (
          <p className="empty">No workflow versions yet — POST /workflows to register one.</p>
        ) : (
          <div className="table-wrap">
            <table className="geist">
              <thead>
                <tr><th>name</th><th>version</th><th>id</th><th></th></tr>
              </thead>
              <tbody>
                {workflows.map((w) => (
                  <tr key={w.id}>
                    <td><b>{w.name}</b></td>
                    <td className="num">v{w.version}</td>
                    <td className="num">{w.id}</td>
                    <td className="num">
                      <button className="btn-secondary" disabled={busy} onClick={() => void onExecute(w.id)}>
                        Execute
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <h3>Execution detail</h3>
        <form
          className="lookup"
          onSubmit={(e) => {
            e.preventDefault();
          }}
        >
          <input
            className="input"
            placeholder="execution id"
            value={execId}
            onChange={(e) => setExecId(e.target.value.replace(/[^0-9]/g, ''))}
          />
          {execution && (
            <button className="btn-secondary" type="button" disabled={busy} onClick={() => void onCancel()}>
              Cancel execution
            </button>
          )}
        </form>
        {!execution && <p className="empty">Execute a workflow above, or type an execution id to inspect it.</p>}
        {execution && (
          <>
            <div className="meta">
              <span className="meta-item">Execution <b>#{execution.id}</b></span>
              <span className="meta-item">Workflow <b>{execution.workflowId}</b> v{execution.workflowVersion}</span>
              <span className="meta-item"><Badge value={execution.status} /></span>
            </div>
            <div className="table-wrap">
              <table className="geist">
                <thead>
                  <tr><th>task</th><th>status</th><th>attempt</th><th>worker</th><th>error</th></tr>
                </thead>
                <tbody>
                  {execution.tasks.map((t) => (
                    <tr key={t.taskId}>
                      <td><code>{t.taskId}</code></td>
                      <td><Badge value={t.status} /></td>
                      <td className="num">{t.attempt}</td>
                      <td className="num">{t.workerId ?? '—'}</td>
                      <td>{t.error ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <h4 className="subhead">Event log</h4>
            <ol className="timeline">
              {execution.events.map((ev, i) => (
                <li key={i}>
                  <code>{ev.at}</code> <b>{ev.status}</b>{ev.taskId ? ` ${ev.taskId}` : ''}{' '}
                  <span className="dim">
                    attempt {ev.attempt}
                    {ev.workerId ? ` · ${ev.workerId}` : ''}
                    {ev.detail ? ` · ${ev.detail}` : ''}
                  </span>
                </li>
              ))}
            </ol>
          </>
        )}
      </section>

      {stats && (
        <section className="card">
          <h3>Workers</h3>
          {stats.workers.length === 0 ? (
            <p className="empty">No workers registered — start one with heartbeat to appear here.</p>
          ) : (
            <div className="table-wrap">
              <table className="geist">
                <thead>
                  <tr><th>id</th><th>hostname</th><th>status</th><th>cpu</th><th>memory</th><th>heartbeat</th></tr>
                </thead>
                <tbody>
                  {stats.workers.map((w) => (
                    <tr key={w.id}>
                      <td><code>{w.id}</code></td>
                      <td>{w.hostname}</td>
                      <td><Badge value={w.status} /></td>
                      <td className="num">{w.cpuCapacity}</td>
                      <td className="num">{w.memoryCapacityMb} MB</td>
                      <td className="num">{w.lastHeartbeat ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {stats && stats.recentFailures.length > 0 && (
        <section className="card">
          <h3>Recent failures</h3>
          <div className="table-wrap">
            <table className="geist">
              <thead>
                <tr><th>execution</th><th>task</th><th>attempt</th><th>error</th></tr>
              </thead>
              <tbody>
                {stats.recentFailures.map((f, i) => (
                  <tr key={`${f.executionId}-${f.taskId}-${i}`}>
                    <td className="num">{f.executionId}</td>
                    <td><code>{f.taskId}</code></td>
                    <td className="num">{f.attempt}</td>
                    <td>{f.error ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
