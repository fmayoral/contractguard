import { useState } from 'react';
import { api, ApiError } from '../api';
import { shortHash } from '../format';
import type { ApprovalInfo, Plan } from '../types';

interface PlanApprovalProps {
  runId: string;
  state: string;
  plan: Plan | null;
  approval: ApprovalInfo | null;
  onChanged: () => void;
}

/** Migration plan review with the explicit human gate (FR-010). */
export function PlanApproval({ runId, state, plan, approval, onChanged }: PlanApprovalProps) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!plan) {
    return <p className="hint">No migration plan yet.</p>;
  }

  const act = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError && e.remediation ? `${e.message} — ${e.remediation}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  const canDecide = state === 'AWAITING_APPROVAL' && !approval;
  const canExecute = state === 'AWAITING_APPROVAL' && approval?.decision === 'APPROVED';

  return (
    <div>
      <p className="muted">
        Plan v{plan.version} · hash <code>{shortHash(plan.hash)}</code>
      </p>
      {plan.items.map((item) => (
        <article key={item.id} className="plan-item">
          <strong>{item.objective}</strong>
          <p>{item.proposedAction}</p>
          <dl>
            <dt>Files</dt>
            <dd>
              {item.expectedFiles.map((file) => (
                <code key={file}>{file}</code>
              ))}
            </dd>
            <dt>Tests</dt>
            <dd>
              {item.testsToUpdate.length === 0
                ? '—'
                : item.testsToUpdate.map((file) => <code key={file}>{file}</code>)}
            </dd>
            <dt>Validation</dt>
            <dd>
              <code>{item.validationCommand}</code>
            </dd>
            <dt>Risk</dt>
            <dd>{item.risk}</dd>
            <dt>Rollback</dt>
            <dd>{item.rollback}</dd>
          </dl>
        </article>
      ))}
      <p className="muted">Approved files: {plan.approvedFiles.length}</p>

      {approval && (
        <p>
          Decision: <strong>{approval.decision}</strong> at {approval.decidedAt}
        </p>
      )}

      {canDecide && (
        <div className="approval-controls">
          <p className="warning">
            ⚠ Approving authorises ContractGuard to modify the files above on an isolated branch.
            Nothing has been modified yet.
          </p>
          <button
            className="approve"
            disabled={busy}
            onClick={() => act(() => api.decide(runId, 'APPROVED', plan.hash))}
          >
            Approve plan
          </button>
          <button
            className="reject"
            disabled={busy}
            onClick={() => act(() => api.decide(runId, 'REJECTED', plan.hash))}
          >
            Reject
          </button>
        </div>
      )}

      {canExecute && (
        <div className="approval-controls">
          <button className="approve" disabled={busy} onClick={() => act(() => api.execute(runId))}>
            Execute remediation
          </button>
        </div>
      )}

      {error && <p className="error">{error}</p>}
    </div>
  );
}
