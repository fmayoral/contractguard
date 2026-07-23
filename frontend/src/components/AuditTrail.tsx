import { useState } from 'react';
import { api } from '../api';
import { formatTimestamp, shortHash } from '../format';
import type { AuditEntry } from '../types';

interface AuditTrailProps {
  runId: string;
}

const EVENT_LABELS: Record<string, string> = {
  STATE_TRANSITION: 'state',
  APPROVAL_DECISION: 'approval',
  REPOSITORY_MUTATION: 'mutation',
};

/** Read-only compliance trail (FR-025): state transitions, approval decisions and repository
 * mutations, principal-attributed. Loaded on demand, same pattern as the report view. */
export function AuditTrail({ runId }: AuditTrailProps) {
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setError(null);
    try {
      setEntries(await api.auditTrail(runId));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div>
      {!entries && <button onClick={load}>Load audit trail</button>}
      {error && <p className="error">{error}</p>}
      {entries && entries.length === 0 && <p className="hint">No audit entries recorded yet.</p>}
      {entries && entries.length > 0 && (
        <ol className="audit-trail">
          {entries.map((entry) => {
            const kind = entry.eventType.toLowerCase();
            return (
              <li key={entry.id} className="audit-item">
                <span className="audit-time">{formatTimestamp(entry.occurredAt)}</span>
                <span className={`audit-kind kind-${kind}`}>{EVENT_LABELS[entry.eventType] ?? kind}</span>
                <span className="audit-detail">
                  {entry.detail}
                  {entry.planHash && <code className="audit-hash">{shortHash(entry.planHash)}</code>}
                </span>
                <span className="audit-principal muted">{entry.principal}</span>
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}
