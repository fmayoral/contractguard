interface HBarListProps {
  /** Already sorted most-frequent-first (the API contract). */
  entries: Record<string, number>;
  maxRows?: number;
}

/**
 * Horizontal magnitude comparison over nominal categories: one series, so every
 * bar wears the same accent hue (a per-category rainbow or value-ramp would
 * re-encode what bar length already shows). Every value is directly labelled at
 * the bar end — the list is short by construction, folding the tail to "Other".
 */
export function HBarList({ entries, maxRows = 8 }: HBarListProps) {
  const rows = Object.entries(entries);
  const shown = rows.slice(0, maxRows);
  const folded = rows.slice(maxRows);
  if (folded.length > 0) {
    shown.push(['Other', folded.reduce((sum, [, count]) => sum + count, 0)]);
  }
  const max = Math.max(1, ...shown.map(([, count]) => count));

  if (shown.length === 0) {
    return <p className="hint">No changes detected yet.</p>;
  }

  return (
    <div className="hbar-list">
      {shown.map(([label, count]) => (
        <div key={label} className="hbar-row">
          <span className="hbar-label" title={label}>
            {label.replaceAll('_', ' ').toLowerCase()}
          </span>
          <div className="hbar-track">
            <div className="hbar-fill" style={{ width: `${(count / max) * 100}%` }} />
            <span className="hbar-count">{count}</span>
          </div>
        </div>
      ))}
    </div>
  );
}
