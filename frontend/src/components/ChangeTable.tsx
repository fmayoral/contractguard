import { Badge } from './Badge';
import { changeTarget, classificationTone } from '../format';
import type { Change } from '../types';

interface ChangeTableProps {
  changes: Change[];
}

export function ChangeTable({ changes }: ChangeTableProps) {
  if (changes.length === 0) {
    return <p className="hint">No changes detected yet.</p>;
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Classification</th>
          <th>Type</th>
          <th>Endpoint / schema</th>
          <th>Old</th>
          <th>New</th>
          <th>Explanation</th>
        </tr>
      </thead>
      <tbody>
        {changes.map((change) => (
          <tr key={change.id}>
            <td>
              <Badge tone={classificationTone(change.classification)}>{change.classification}</Badge>
            </td>
            <td>{change.type}</td>
            <td>
              <code>{changeTarget(change)}</code>
            </td>
            <td>{change.oldValue ? <code>{change.oldValue}</code> : '—'}</td>
            <td>{change.newValue ? <code>{change.newValue}</code> : '—'}</td>
            <td className="wrap">{change.explanation ?? change.reason}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
