import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { formatDuration, isTerminal, stateTone } from '../format';
import { LIVE_RUNS_POLL_MS, useLiveRuns } from '../useLiveRuns';
import { Badge } from '../components/Badge';
import { BarTrend } from '../components/charts/BarTrend';
import { Donut, type DonutSegment } from '../components/charts/Donut';
import { HBarList } from '../components/charts/HBarList';
import { SegmentBar, type Segment } from '../components/charts/SegmentBar';
import { StatTile } from '../components/charts/StatTile';
import type { Statistics } from '../types';

const RECENT_RUNS = 5;

function count(byState: Record<string, number>, ...states: string[]): number {
  return states.reduce((sum, state) => sum + (byState[state] ?? 0), 0);
}

/** Segment order in these two builders is CVD-load-bearing — see charts/tones.ts. */
function outcomeSegments(stats: Statistics): DonutSegment[] {
  const byState = stats.runsByState;
  const succeeded = count(byState, 'SUCCEEDED', 'PUBLISHED');
  const failed = count(byState, 'FAILED', 'PUBLISH_FAILED');
  const declined = count(byState, 'REJECTED', 'CANCELLED');
  const awaiting = count(byState, 'AWAITING_APPROVAL');
  const inProgress = stats.activeRuns - awaiting;
  return [
    { label: 'Succeeded', count: succeeded, tone: 'ok' },
    { label: 'Awaiting approval', count: awaiting, tone: 'warn' },
    { label: 'Rejected / cancelled', count: declined, tone: 'muted' },
    { label: 'Failed', count: failed, tone: 'bad' },
    { label: 'In progress', count: Math.max(0, inProgress), tone: 'accent' },
  ];
}

function classificationSegments(stats: Statistics): Segment[] {
  const byClass = stats.changesByClassification;
  return [
    { label: 'Breaking', count: byClass.BREAKING ?? 0, tone: 'bad' },
    { label: 'Unknown', count: byClass.UNKNOWN ?? 0, tone: 'muted' },
    { label: 'Potentially breaking', count: byClass.POTENTIALLY_BREAKING ?? 0, tone: 'warn' },
    { label: 'Non-breaking', count: byClass.NON_BREAKING ?? 0, tone: 'ok' },
  ];
}

function successRate(stats: Statistics): string {
  const succeeded = count(stats.runsByState, 'SUCCEEDED', 'PUBLISHED');
  const terminal = succeeded + count(stats.runsByState, 'FAILED', 'PUBLISH_FAILED', 'REJECTED', 'CANCELLED');
  return terminal === 0 ? '—' : `${Math.round((succeeded / terminal) * 100)}%`;
}

export function DashboardPage() {
  const [stats, setStats] = useState<Statistics | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { runs } = useLiveRuns();

  useEffect(() => {
    const load = () => {
      api.statistics().then(setStats).catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
    };
    load();
    const id = setInterval(load, LIVE_RUNS_POLL_MS);
    return () => clearInterval(id);
  }, []);

  const activeRuns = runs.filter((run) => !isTerminal(run.state));

  if (error) {
    return (
      <section className="card">
        <p className="error">{error}</p>
      </section>
    );
  }
  if (!stats) {
    return (
      <section className="card">
        <p className="hint">Loading statistics…</p>
      </section>
    );
  }

  const remediation = stats.remediation;
  const breaking = stats.changesByClassification.BREAKING ?? 0;

  if (stats.totalRuns === 0) {
    return (
      <div className="page">
        <header className="page-header">
          <h2>Dashboard</h2>
          <p className="muted">Contract-change analysis at a glance.</p>
        </header>
        <section className="card empty-state">
          <h3>No runs yet</h3>
          <p className="hint">
            Register your sources in <Link to="/settings">Settings</Link>, then start your first
            analysis to see impact statistics here.
          </p>
          <Link className="button-link" to="/new">
            Start your first run
          </Link>
        </section>
      </div>
    );
  }

  return (
    <div className="page">
      <header className="page-header">
        <h2>Dashboard</h2>
        <p className="muted">Contract-change analysis at a glance.</p>
      </header>

      {activeRuns.length > 0 && (
        <section className="card active-runs-card">
          <div className="active-runs-heading">
            <span className="progress-dot" aria-hidden="true" />
            {activeRuns.length} run{activeRuns.length === 1 ? '' : 's'} in progress
          </div>
          <ul className="active-runs-list">
            {activeRuns.map((run) => (
              <li key={run.id}>
                <Link to={`/runs/${run.id}`}>{run.name}</Link>
                <span className="muted">{run.repositoryId}</span>
                <Badge tone={stateTone(run.state)}>{run.state}</Badge>
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="stat-row">
        <StatTile
          label="Total runs"
          value={String(stats.totalRuns)}
          detail={stats.activeRuns > 0 ? `${stats.activeRuns} active now` : 'none active'}
        />
        <StatTile label="Success rate" value={successRate(stats)} detail="of finished runs" />
        <StatTile label="Breaking changes" value={String(breaking)} detail={`of ${stats.totalChanges} detected`} />
        <StatTile
          label="Lines remediated"
          value={`+${remediation.linesAdded} −${remediation.linesRemoved}`}
          detail={`${remediation.filesTouched} file${remediation.filesTouched === 1 ? '' : 's'} touched`}
        />
      </div>

      <div className="chart-grid">
        <section className="card">
          <h3>Activity</h3>
          <p className="muted chart-subtitle">Runs per day, last {stats.runsPerDay.length} days</p>
          <BarTrend data={stats.runsPerDay} />
          <details className="chart-data">
            <summary>Data</summary>
            <table>
              <thead>
                <tr>
                  <th>Day</th>
                  <th>Runs</th>
                </tr>
              </thead>
              <tbody>
                {stats.runsPerDay.map((d) => (
                  <tr key={d.day}>
                    <td>{d.day}</td>
                    <td>{d.count}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        </section>

        <section className="card">
          <h3>Outcomes</h3>
          <p className="muted chart-subtitle">Every run by where it ended up</p>
          <Donut segments={outcomeSegments(stats)} centerLabel="runs" />
        </section>
      </div>

      <div className="chart-grid">
        <section className="card">
          <h3>Contract changes</h3>
          <p className="muted chart-subtitle">Classification split and detected categories</p>
          <SegmentBar segments={classificationSegments(stats)} />
          <HBarList entries={stats.changesByType} />
        </section>

        <section className="card">
          <h3>Remediation</h3>
          <p className="muted chart-subtitle">Validation and the single bounded repair</p>
          <dl className="fact-list">
            <div>
              <dt>Runs validated</dt>
              <dd>{remediation.validatedRuns}</dd>
            </div>
            <div>
              <dt>Passed first attempt</dt>
              <dd>{remediation.firstPassRuns}</dd>
            </div>
            <div>
              <dt>Repairs attempted</dt>
              <dd>{remediation.repairAttempts}</dd>
            </div>
            <div>
              <dt>Saved by repair</dt>
              <dd>{remediation.repairedRuns}</dd>
            </div>
            <div>
              <dt>Avg validation time</dt>
              <dd>{remediation.validatedRuns === 0 ? '—' : formatDuration(remediation.averageValidationMillis)}</dd>
            </div>
          </dl>
        </section>
      </div>

      <section className="card">
        <div className="card-header-row">
          <h3>Recent runs</h3>
          <Link className="muted" to="/runs">
            View all →
          </Link>
        </div>
        <ul className="recent-runs">
          {runs.slice(0, RECENT_RUNS).map((run) => (
            <li key={run.id}>
              <Link to={`/runs/${run.id}`} className="recent-run">
                <span className="run-name">{run.name}</span>
                <span className="muted">{run.repositoryId}</span>
                <Badge tone={stateTone(run.state)}>{run.state}</Badge>
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
