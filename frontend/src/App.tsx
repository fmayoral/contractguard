import { useCallback, useEffect, useState } from 'react';
import { api } from './api';
import { isTerminal, stateTone } from './format';
import { useRunEvents } from './useRunEvents';
import { Badge } from './components/Badge';
import { ChangeTable } from './components/ChangeTable';
import { ImpactView } from './components/ImpactView';
import { PlanApproval } from './components/PlanApproval';
import { PublishPanel } from './components/PublishPanel';
import { ReportView } from './components/ReportView';
import { RunList } from './components/RunList';
import { RunSetup } from './components/RunSetup';
import { ThemeToggle } from './components/ThemeToggle';
import { Timeline } from './components/Timeline';
import { ValidationView } from './components/ValidationView';
import type { RunDetail, RunSummary } from './types';

export default function App() {
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [run, setRun] = useState<RunDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const refreshRuns = useCallback(() => {
    api.listRuns().then(setRuns).catch(() => setRuns([]));
  }, []);

  const refreshRun = useCallback(() => {
    if (!selectedId) return;
    api
      .getRun(selectedId)
      .then((detail) => {
        setRun(detail);
        setLoadError(null);
      })
      .catch((e: unknown) => setLoadError(e instanceof Error ? e.message : String(e)));
  }, [selectedId]);

  useEffect(refreshRuns, [refreshRuns]);
  useEffect(refreshRun, [refreshRun]);

  // Every SSE event refreshes both the run detail and the history list.
  const events = useRunEvents(selectedId, () => {
    refreshRun();
    refreshRuns();
  });

  const onCreated = (runId: string) => {
    setSelectedId(runId);
    refreshRuns();
  };

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="sidebar-header">
          <h1>ContractGuard AI</h1>
          <ThemeToggle />
        </div>
        <RunSetup onCreated={onCreated} />
        <section className="card">
          <h2>Run history</h2>
          <RunList runs={runs} selectedId={selectedId} onSelect={setSelectedId} />
        </section>
      </aside>

      <main className="content">
        {!run && (
          <section className="card">
            <p className="hint">
              {loadError ?? 'Select or start a run to see its analysis, plan and remediation.'}
            </p>
          </section>
        )}
        {run && (
          <>
            <header className="run-header">
              <div>
                <h2>{run.name}</h2>
                <p className="muted">
                  {run.repositoryId} · {run.oldSpecName ?? '…'} → {run.newSpecName ?? '…'} · trace{' '}
                  <code>{run.traceId.slice(0, 8)}</code>
                </p>
              </div>
              <Badge tone={stateTone(run.state)}>{run.state}</Badge>
            </header>

            {run.failure && (
              <section className="card failure-card">
                <h3>Run failed: {run.failure.category}</h3>
                <p>{run.failure.message}</p>
                <p>
                  Repository mutated: <strong>{run.failure.mutationOccurred ? 'yes' : 'no'}</strong>
                </p>
                <p className="muted">{run.failure.remediation}</p>
              </section>
            )}

            <section className="card">
              <h3>Timeline</h3>
              <Timeline events={events} />
            </section>

            <section className="card">
              <h3>Contract changes</h3>
              <ChangeTable changes={run.changes} />
            </section>

            <section className="card">
              <h3>Consumer impact</h3>
              <ImpactView assessments={run.assessments} evidence={run.evidence} />
            </section>

            <section className="card">
              <h3>Migration plan &amp; approval</h3>
              <PlanApproval
                runId={run.id}
                state={run.state}
                plan={run.plan}
                approval={run.approval}
                onChanged={() => {
                  refreshRun();
                  refreshRuns();
                }}
              />
            </section>

            <section className="card">
              <h3>Remediation &amp; validation</h3>
              <ValidationView
                originalBranch={run.originalBranch}
                workingBranch={run.workingBranch}
                patches={run.patches}
                validations={run.validations}
              />
            </section>

            <section className="card">
              <h3>Publish</h3>
              <PublishPanel
                runId={run.id}
                state={run.state}
                remoteRepository={run.remoteRepository}
                pullRequestUrl={run.pullRequestUrl}
                onChanged={() => {
                  refreshRun();
                  refreshRuns();
                }}
              />
            </section>

            <section className="card">
              <h3>Report</h3>
              <ReportView runId={run.id} terminal={isTerminal(run.state)} />
            </section>
          </>
        )}
      </main>
    </div>
  );
}
