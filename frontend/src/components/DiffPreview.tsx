import { useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { ChangeTable } from './ChangeTable';
import { SegmentBar, type Segment } from './charts/SegmentBar';
import type { Change } from '../types';

interface DiffPreviewProps {
  oldSpec: string;
  newSpec: string;
}

/** Segment order matches DashboardPage's classificationSegments -- CVD-load-bearing
 * order, see charts/tones.ts -- so the same classification always reads as the same
 * colour everywhere in the app. */
function classificationSegments(changes: Change[]): Segment[] {
  const count = (classification: string) => changes.filter((c) => c.classification === classification).length;
  return [
    { label: 'Breaking', count: count('BREAKING'), tone: 'bad' },
    { label: 'Unknown', count: count('UNKNOWN'), tone: 'muted' },
    { label: 'Potentially breaking', count: count('POTENTIALLY_BREAKING'), tone: 'warn' },
    { label: 'Non-breaking', count: count('NON_BREAKING'), tone: 'ok' },
  ];
}

/**
 * Instant, zero-cost preview of what a run would detect between the two selected
 * specifications: the same deterministic diff a real run computes first, with nothing
 * persisted and no LLM involved (§ADR-0017). Deliberately not a preview of consumer
 * impact -- that needs a real repository and the full analysis.
 */
export function DiffPreview({ oldSpec, newSpec }: DiffPreviewProps) {
  const [changes, setChanges] = useState<Change[] | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setChanges(null);
    setError(null);
    setExpanded(false);
    api.previewSpecDiff(oldSpec, newSpec).then((preview) => {
      if (cancelled) return;
      setChanges(preview.changes);
      setWarnings(preview.warnings);
    }).catch((e: unknown) => {
      if (cancelled) return;
      setError(e instanceof ApiError && e.remediation ? `${e.message} — ${e.remediation}` : String(e));
    });
    return () => {
      cancelled = true;
    };
  }, [oldSpec, newSpec]);

  if (error) {
    return (
      <div className="diff-preview">
        <p className="error">{error}</p>
      </div>
    );
  }
  if (!changes) {
    return (
      <div className="diff-preview">
        <p className="hint">Comparing specifications…</p>
      </div>
    );
  }

  return (
    <div className="diff-preview">
      <div className="card-header-row">
        <strong>
          Preview: {changes.length} change{changes.length === 1 ? '' : 's'} detected
        </strong>
        {changes.length > 0 && (
          <button className="secondary small" onClick={() => setExpanded((v) => !v)}>
            {expanded ? 'Hide details' : 'Show details'}
          </button>
        )}
      </div>
      <p className="muted">Deterministic diff only — consumer impact needs the full analysis.</p>
      {changes.length > 0 && <SegmentBar segments={classificationSegments(changes)} />}
      {warnings.length > 0 && (
        <ul className="hint">
          {warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
      {expanded && <ChangeTable changes={changes} />}
    </div>
  );
}
