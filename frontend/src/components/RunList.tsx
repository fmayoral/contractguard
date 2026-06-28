import { Badge } from './Badge';
import { stateTone } from '../format';
import type { RunSummary } from '../types';

interface RunListProps {
  runs: RunSummary[];
  selectedId: string | null;
  onSelect: (runId: string) => void;
}

export function RunList({ runs, selectedId, onSelect }: RunListProps) {
  if (runs.length === 0) {
    return <p className="hint">No runs yet.</p>;
  }
  return (
    <ul className="run-list">
      {runs.map((run) => (
        <li key={run.id}>
          <button
            className={run.id === selectedId ? 'run-item selected' : 'run-item'}
            onClick={() => onSelect(run.id)}
          >
            <span className="run-name">{run.name}</span>
            <Badge tone={stateTone(run.state)}>{run.state}</Badge>
          </button>
        </li>
      ))}
    </ul>
  );
}
