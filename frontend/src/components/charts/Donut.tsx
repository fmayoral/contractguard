import { toneColor, type Tone } from './tones';

export interface DonutSegment {
  label: string;
  count: number;
  tone: Tone;
}

interface DonutProps {
  /** Pre-ordered by the caller — segment order is CVD-load-bearing (see tones.ts). */
  segments: DonutSegment[];
  centerLabel: string;
}

const SIZE = 160;
const RADIUS = 62;
const THICKNESS = 18;
/** Angular gap between segments, the donut's version of the 2px surface gap. */
const GAP_DEGREES = 3;

function arcPath(startDeg: number, endDeg: number): string {
  const toXY = (deg: number) => {
    const rad = ((deg - 90) * Math.PI) / 180;
    return [SIZE / 2 + RADIUS * Math.cos(rad), SIZE / 2 + RADIUS * Math.sin(rad)];
  };
  const [x1, y1] = toXY(startDeg);
  const [x2, y2] = toXY(endDeg);
  const largeArc = endDeg - startDeg > 180 ? 1 : 0;
  return `M ${x1} ${y1} A ${RADIUS} ${RADIUS} 0 ${largeArc} 1 ${x2} ${y2}`;
}

/**
 * Part-to-whole of run outcomes. Every value is visible in the legend beside it
 * (counts included), so colour is never the only channel and no tooltip gates a
 * value. A single non-zero segment renders as a full ring.
 */
export function Donut({ segments, centerLabel }: DonutProps) {
  const visible = segments.filter((s) => s.count > 0);
  const total = visible.reduce((sum, s) => sum + s.count, 0);

  let angle = 0;
  const arcs = visible.map((segment) => {
    const span = (segment.count / total) * 360;
    const gap = visible.length > 1 ? GAP_DEGREES : 0;
    const start = angle + gap / 2;
    // A full-circle arc has coincident endpoints and renders as nothing; cap just short.
    const end = Math.min(angle + span - gap / 2, angle + span - 0.01);
    angle += span;
    return { ...segment, start, end };
  });

  return (
    <div className="donut-layout">
      <svg
        className="chart donut"
        viewBox={`0 0 ${SIZE} ${SIZE}`}
        role="img"
        aria-label={`Run outcomes: ${visible.map((s) => `${s.label} ${s.count}`).join(', ') || 'no runs yet'}`}
      >
        {arcs.length === 0 && (
          <circle cx={SIZE / 2} cy={SIZE / 2} r={RADIUS} className="donut-empty-track" strokeWidth={THICKNESS} />
        )}
        {arcs.map((arc) => (
          <path
            key={arc.label}
            d={arcPath(arc.start, arc.end)}
            fill="none"
            stroke={toneColor(arc.tone)}
            strokeWidth={THICKNESS}
            strokeLinecap="butt"
          >
            <title>{`${arc.label}: ${arc.count}`}</title>
          </path>
        ))}
        <text x={SIZE / 2} y={SIZE / 2 - 2} className="donut-center-value" textAnchor="middle">
          {total}
        </text>
        <text x={SIZE / 2} y={SIZE / 2 + 16} className="donut-center-label" textAnchor="middle">
          {centerLabel}
        </text>
      </svg>
      <ul className="chart-legend">
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
