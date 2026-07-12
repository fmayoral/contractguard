import { Badge } from './Badge';
import { DiffView } from './DiffView';
import { formatDuration } from '../format';
import type { Patch, Validation } from '../types';

interface ValidationViewProps {
  originalBranch: string | null;
  workingBranch: string | null;
  patches: Patch[];
  validations: Validation[];
}

export function ValidationView({ originalBranch, workingBranch, patches, validations }: ValidationViewProps) {
  if (!workingBranch && patches.length === 0 && validations.length === 0) {
    return <p className="hint">Remediation has not started.</p>;
  }
  const repaired = patches.some((patch) => patch.attempt > 1);
  return (
    <div>
      {workingBranch && (
        <p>
          Working branch <code>{workingBranch}</code> (from <code>{originalBranch}</code>)
        </p>
      )}
      {repaired && <Badge tone="warn">repair attempt used</Badge>}
      {patches.map((patch) => (
        <article key={patch.id} className="patch-card">
          <header>
            Patch attempt {patch.attempt} · <Badge tone={patch.checkStatus === 'APPLIED' ? 'ok' : 'warn'}>{patch.checkStatus}</Badge>
          </header>
          <p className="muted">Files: {patch.changedPaths.join(', ')}</p>
          <details>
            <summary>Unified diff</summary>
            <DiffView diff={patch.unifiedDiff} />
          </details>
        </article>
      ))}
      {validations.map((validation) => (
        <article key={validation.attempt} className="validation-card">
          <header>
            Validation attempt {validation.attempt} ·{' '}
            <Badge tone={validation.successful ? 'ok' : 'bad'}>
              {validation.successful ? 'PASSED' : 'FAILED'}
            </Badge>
          </header>
          <p>
            <code>{validation.command}</code> → exit {validation.exitCode} in{' '}
            {formatDuration(validation.durationMillis)}
          </p>
          <p className="muted">{validation.summary}</p>
        </article>
      ))}
    </div>
  );
}
