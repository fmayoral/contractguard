import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api';
import { isTerminal, stateTone } from '../format';
import { useAutoScrollToBottom } from '../useAutoScrollToBottom';
import { useRunEvents } from '../useRunEvents';
import { AnalysisProgress } from '../components/AnalysisProgress';
import { AuditTrail } from '../components/AuditTrail';
import { Badge } from '../components/Badge';
import { ChangeTable } from '../components/ChangeTable';
import { ImpactView } from '../components/ImpactView';
import { PlanApproval } from '../components/PlanApproval';
import { PublishPanel } from '../components/PublishPanel';
import { ReportView } from '../components/ReportView';
import { ValidationView } from '../components/ValidationView';
import type { RunDetail } from '../types';

const HIGHLIGHT_DURATION_MS = 1600;

/** One run's whole lifecycle: progress, changes, impact, plan, remediation, publish, report. */
export function RunDetailPage() {
  const { runId } = useParams<{ runId: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const [run, setRun] = useState<RunDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const scrolledForKey = useRef<string | null>(null);

  const refreshRun = useCallback(() => {
    if (!runId) return;
    api
      .getRun(runId)
      .then((detail) => {
        setRun(detail);
        setLoadError(null);
      })
      .catch((e: unknown) => setLoadError(e instanceof Error ? e.message : String(e)));
  }, [runId]);

  useEffect(refreshRun, [refreshRun]);

  const events = useRunEvents(runId ?? null, refreshRun);

  // Shared "something about this run just changed" signal -- ticks on every pushed timeline
  // event and every refetched run, so anything downstream that wants to stay live can key off
  // one value instead of re-deriving it.
  const activitySignal = `${events.length}:${run?.updatedAt ?? ''}`;

  useAutoScrollToBottom(runId ?? null, activitySignal);

  // A notification click carries { scrollTo: <element id> } via navigation state -- e.g.
  // action-required toasts land the user on this exact spot instead of the page top. Guarded
  // by location.key so it fires once per navigation, not on every subsequent run refresh.
  useEffect(() => {
    const target = (location.state as { scrollTo?: string } | null)?.scrollTo;
    if (!run || !target || scrolledForKey.current === location.key) return;
    const el = document.getElementById(target);
    if (!el) return;
    scrolledForKey.current = location.key;
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    el.classList.add('highlight-pulse');
    const timer = setTimeout(() => el.classList.remove('highlight-pulse'), HIGHLIGHT_DURATION_MS);
    return () => clearTimeout(timer);
  }, [run, location.state, location.key]);

  if (loadError) {
    return (
      <div className="page">
        <section className="card">
          <p className="error">{loadError}</p>
          <Link to="/runs">← Back to runs</Link>
        </section>
      </div>
    );
  }
  if (!run) {
    return (
      <div className="page">
        <section className="card">
          <p className="hint">Loading run…</p>
        </section>
      </div>
    );
  }

  return (
    <div className="page">
      <header className="run-header">
        <div>
          <p className="breadcrumb">
            <Link to="/runs">Runs</Link> / {run.name}
          </p>
          <h2>{run.name}</h2>
          <p className="muted">
            {run.repositoryId} · {run.oldSpecName ?? '…'} → {run.newSpecName ?? '…'} · trace{' '}
            <code>{run.traceId.slice(0, 8)}</code>
          </p>
        </div>
        <div className="run-header-actions">
          {run.oldSpecFile && run.newSpecFile && (
            <button
              className="secondary small"
              onClick={() =>
                navigate('/new', {
                  state: { repositoryId: run.repositoryId, oldSpec: run.oldSpecFile, newSpec: run.newSpecFile },
                })
              }
            >
              Run again
            </button>
          )}
          <Badge tone={stateTone(run.state)}>{run.state}</Badge>
        </div>
      </header>

      <AnalysisProgress state={run.state} since={run.createdAt} until={run.updatedAt} events={events} />

      {run.failure && (
        <section className="card failure-card" id="failure">
          <h3>Run failed: {run.failure.category}</h3>
          <p>{run.failure.message}</p>
          <p>
            Repository mutated: <strong>{run.failure.mutationOccurred ? 'yes' : 'no'}</strong>
          </p>
          <p className="muted">{run.failure.remediation}</p>
        </section>
      )}

      <section className="card">
        <h3>Contract changes</h3>
        <ChangeTable changes={run.changes} />
      </section>

      <section className="card">
        <h3>Consumer impact</h3>
        <ImpactView assessments={run.assessments} evidence={run.evidence} />
      </section>

      <section className="card" id="plan-approval">
        <h3>Migration plan &amp; approval</h3>
        <PlanApproval
          runId={run.id}
          state={run.state}
          plan={run.plan}
          approval={run.approval}
          onChanged={refreshRun}
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

      <section className="card" id="publish">
        <h3>Publish</h3>
        <PublishPanel
          runId={run.id}
          state={run.state}
          remoteRepository={run.remoteRepository}
          pullRequestUrl={run.pullRequestUrl}
          onChanged={refreshRun}
        />
      </section>

      <section className="card">
        <h3>Report</h3>
        <ReportView runId={run.id} terminal={isTerminal(run.state)} />
      </section>

      <section className="card">
        <h3>Audit trail</h3>
        <AuditTrail runId={run.id} refreshSignal={activitySignal} />
      </section>
    </div>
  );
}
