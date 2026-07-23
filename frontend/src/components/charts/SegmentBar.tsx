import { toneColor, type Tone } from './tones';

export interface Segment {
  label: string;
  count: number;
  tone: Tone;
}

interface SegmentBarProps {
  /** Pre-ordered by the caller — segment order is CVD-load-bearing (see tones.ts). */
  segments: Segment[];
}

/**
 * One horizontal stacked bar for a part-to-whole split, with the 2px surface gap
 * doing the separating and a legend carrying every label + count (so colour is
 * never the only channel).
 */
export function SegmentBar({ segments }: SegmentBarProps) {
  const visible = segments.filter((s) => s.count > 0);
  const total = visible.reduce((sum, s) => sum + s.count, 0);

  if (total === 0) {
    return <p className="hint">No changes classified yet.</p>;
  }

  return (
    <div className="segment-bar-layout">
      <div className="segment-bar" role="img" aria-label={visible.map((s) => `${s.label} ${s.count}`).join(', ')}>
        {visible.map((segment) => (
          <div
            key={segment.label}
            className="segment"
            style={{ width: `${(segment.count / total) * 100}%`, background: toneColor(segment.tone) }}
            title={`${segment.label}: ${segment.count}`}
          />
        ))}
      </div>
      <ul className="chart-legend horizontal">
        {segments.map((segment) => (
          <li key={segment.label} className={segment.count === 0 ? 'legend-zero' : undefined}>
            <span className="legend-swatch" style={{ background: toneColor(segment.tone) }} />
            <span className="legend-label">{segment.label}</span>
            <span className="legend-count">{segment.count}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
