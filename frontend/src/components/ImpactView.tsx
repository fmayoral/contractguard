import { Badge } from './Badge';
import { severityTone } from '../format';
import type { Assessment, Evidence } from '../types';

interface ImpactViewProps {
  assessments: Assessment[];
  evidence: Evidence[];
}

/** Assessments with their file-and-line evidence (FR-008). */
export function ImpactView({ assessments, evidence }: ImpactViewProps) {
  if (assessments.length === 0) {
    return <p className="hint">No impact assessments yet.</p>;
  }
  const byId = new Map(evidence.map((item) => [item.id, item]));
  return (
    <div className="impact-list">
      {assessments.map((assessment) => (
        <article key={assessment.id} className="impact-card">
          <header>
            <strong>{assessment.component}</strong>
            <Badge tone={severityTone(assessment.severity)}>{assessment.severity}</Badge>
            <span className="muted">confidence {assessment.confidence}</span>
          </header>
          <p>
            <em>Failure mode:</em> {assessment.failureMode}
          </p>
          <p>
            <em>Recommended:</em> {assessment.recommendedAction}
          </p>
          <table className="data-table">
            <thead>
              <tr>
                <th>File</th>
                <th>Line</th>
                <th>Snippet</th>
              </tr>
            </thead>
            <tbody>
              {assessment.evidenceIds.map((id) => {
                const item = byId.get(id);
                return item ? (
                  <tr key={id}>
                    <td>
                      <code>{item.relativePath}</code>
                    </td>
                    <td>{item.startLine}</td>
                    <td>
                      <code className="snippet">{item.snippet}</code>
                    </td>
                  </tr>
                ) : null;
              })}
            </tbody>
          </table>
        </article>
      ))}
    </div>
  );
}
