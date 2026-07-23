import type { DailyCount } from '../../types';

interface BarTrendProps {
  data: DailyCount[];
}

const WIDTH = 560;
const HEIGHT = 150;
const PLOT_TOP = 16;
const AXIS_BAND = 22;
const PLOT_BOTTOM = HEIGHT - AXIS_BAND;
const BAR_MAX_WIDTH = 24;
const BAR_GAP = 2;

/** Rounded at the data end only; square at the baseline. */
function roundedTopBar(x: number, y: number, w: number, h: number): string {
  const r = Math.min(4, w / 2, h);
  return [
    `M ${x} ${y + h}`,
    `L ${x} ${y + r}`,
    `Q ${x} ${y} ${x + r} ${y}`,
    `L ${x + w - r} ${y}`,
    `Q ${x + w} ${y} ${x + w} ${y + r}`,
    `L ${x + w} ${y + h}`,
    'Z',
  ].join(' ');
}

function dayLabel(iso: string): string {
  const date = new Date(`${iso}T00:00:00Z`);
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

/**
 * Runs per day: a single-series column chart, so every bar wears the accent hue
 * and there is no legend — the card title names the series. Values live on the
 * peak bar, the y-axis tick, native tooltips, and the table twin in the card.
 */
export function BarTrend({ data }: BarTrendProps) {
  const max = Math.max(1, ...data.map((d) => d.count));
  const slot = WIDTH / data.length;
  const barWidth = Math.min(BAR_MAX_WIDTH, slot - BAR_GAP * 2);
  const plotHeight = PLOT_BOTTOM - PLOT_TOP;
  const peakIndex = data.reduce((best, d, i) => (d.count > data[best].count ? i : best), 0);

  return (
    <svg
      className="chart bar-trend"
      viewBox={`0 0 ${WIDTH + 34} ${HEIGHT}`}
      role="img"
      aria-label={`Runs per day over the last ${data.length} days`}
    >
      {/* max gridline + tick, then the baseline: recessive solid hairlines */}
      <line x1="34" y1={PLOT_TOP} x2={WIDTH + 34} y2={PLOT_TOP} className="gridline" />
      <text x="28" y={PLOT_TOP + 4} className="axis-text" textAnchor="end">
        {max}
      </text>
      <line x1="34" y1={PLOT_BOTTOM} x2={WIDTH + 34} y2={PLOT_BOTTOM} className="axis-line" />
      <text x="28" y={PLOT_BOTTOM + 4} className="axis-text" textAnchor="end">
        0
      </text>

      {data.map((d, i) => {
        const h = (d.count / max) * plotHeight;
        const x = 34 + i * slot + (slot - barWidth) / 2;
        const isPeak = i === peakIndex && d.count > 0;
        return (
          <g key={d.day}>
            {d.count > 0 && (
              <path d={roundedTopBar(x, PLOT_BOTTOM - h, barWidth, h)} className="bar-fill">
                <title>{`${dayLabel(d.day)}: ${d.count} run${d.count === 1 ? '' : 's'}`}</title>
              </path>
            )}
            {isPeak && (
              <text x={x + barWidth / 2} y={PLOT_BOTTOM - h - 5} className="axis-text" textAnchor="middle">
                {d.count}
              </text>
            )}
          </g>
        );
      })}

      <text x="34" y={HEIGHT - 6} className="axis-text">
        {dayLabel(data[0].day)}
      </text>
      <text x={WIDTH + 34} y={HEIGHT - 6} className="axis-text" textAnchor="end">
        Today
      </text>
    </svg>
  );
}
