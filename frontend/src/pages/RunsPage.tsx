import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { isTerminal, stateTone } from '../format';
import { useLiveRuns } from '../useLiveRuns';
import { Badge } from '../components/Badge';

type StateFilter = 'all' | 'active' | 'finished';

function formatWhen(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function RunsPage() {
  const { runs } = useLiveRuns();
  const [query, setQuery] = useState('');
  const [stateFilter, setStateFilter] = useState<StateFilter>('all');
  const navigate = useNavigate();

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return runs.filter((run) => {
      if (stateFilter === 'active' && isTerminal(run.state)) return false;
      if (stateFilter === 'finished' && !isTerminal(run.state)) return false;
      if (!needle) return true;
      return (
        run.name.toLowerCase().includes(needle) ||
        run.repositoryId.toLowerCase().includes(needle) ||
        run.state.toLowerCase().includes(needle)
      );
    });
  }, [runs, query, stateFilter]);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h2>Runs</h2>
          <p className="muted">Every analysis run, newest first.</p>
        </div>
        <Link className="button-link" to="/new">
          New run
        </Link>
      </header>

      <div className="toolbar">
        <input
          type="search"
          placeholder="Filter by name, repository or state…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Filter runs"
        />
        <select
          value={stateFilter}
          onChange={(e) => setStateFilter(e.target.value as StateFilter)}
          aria-label="Filter by status"
        >
          <option value="all">All runs</option>
          <option value="active">Active</option>
          <option value="finished">Finished</option>
        </select>
      </div>

      {filtered.length === 0 ? (
        <section className="card">
          <p className="hint">
            {runs.length === 0 ? (
              <>
                No runs yet — <Link to="/new">start your first analysis</Link>.
              </>
            ) : (
              'No runs match the current filter.'
            )}
          </p>
        </section>
      ) : (
        <section className="card table-card">
          <table className="run-table">
            <thead>
              <tr>
                <th>Run</th>
                <th>Repository</th>
                <th>Status</th>
                <th>Created</th>
                <th>Updated</th>
                <th aria-label="Pull request" />
              </tr>
            </thead>
            <tbody>
              {filtered.map((run) => (
                <tr key={run.id} onClick={() => navigate(`/runs/${run.id}`)}>
                  <td>
                    <Link to={`/runs/${run.id}`} onClick={(e) => e.stopPropagation()} className="run-table-name">
                      {run.name}
                    </Link>
                  </td>
                  <td className="muted">
                    {run.repositoryId}
                    {run.remoteRepository && <span className="pill">remote</span>}
                  </td>
                  <td>
                    <Badge tone={stateTone(run.state)}>{run.state}</Badge>
                  </td>
                  <td className="muted nowrap">{formatWhen(run.createdAt)}</td>
                  <td className="muted nowrap">{formatWhen(run.updatedAt)}</td>
                  <td>
                    {run.pullRequestUrl && (
                      <a
                        href={run.pullRequestUrl}
                        target="_blank"
                        rel="noreferrer"
                        onClick={(e) => e.stopPropagation()}
                        className="muted"
                      >
                        PR ↗
                      </a>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}
