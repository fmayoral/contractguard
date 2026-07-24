import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { formatDateTime, shortHash } from '../format';
import type { AuditEntry } from '../types';

type EventTypeFilter = 'all' | 'STATE_TRANSITION' | 'APPROVAL_DECISION' | 'REPOSITORY_MUTATION';

const EVENT_TYPE_LABELS: Record<string, string> = {
  STATE_TRANSITION: 'State transition',
  APPROVAL_DECISION: 'Approval decision',
  REPOSITORY_MUTATION: 'Repository mutation',
};

/** Org-wide view over FR-025's audit trail -- every state transition, approval decision and
 * repository mutation across every run, filterable, each row linking back to its run. */
export function AuditLogPage() {
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [repositoryFilter, setRepositoryFilter] = useState('all');
  const [typeFilter, setTypeFilter] = useState<EventTypeFilter>('all');

  const refresh = () => {
    api.auditLog().then(setEntries).catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(refresh, []);

  const repositories = useMemo(
    () => [...new Set((entries ?? []).map((e) => e.repositoryId))].sort(),
    [entries],
  );

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return (entries ?? [])
      .filter((entry) => repositoryFilter === 'all' || entry.repositoryId === repositoryFilter)
      .filter((entry) => typeFilter === 'all' || entry.eventType === typeFilter)
      .filter((entry) => {
        if (!needle) return true;
        return (
          entry.repositoryId.toLowerCase().includes(needle) ||
          entry.runId.toLowerCase().includes(needle) ||
          entry.principal.toLowerCase().includes(needle) ||
          entry.detail.toLowerCase().includes(needle)
        );
      })
      // Newest first -- the port returns oldest first (the natural order to accumulate a trail).
      .slice()
      .reverse();
  }, [entries, query, repositoryFilter, typeFilter]);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h2>Audit log</h2>
          <p className="muted">Every state transition, approval decision and repository mutation, across every run.</p>
        </div>
        <button className="secondary" onClick={refresh}>
          Refresh
        </button>
      </header>

      <div className="toolbar">
        <input
          type="search"
          placeholder="Filter by repository, run, principal or detail…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Filter audit entries"
        />
        <select
          value={repositoryFilter}
          onChange={(e) => setRepositoryFilter(e.target.value)}
          aria-label="Filter by repository"
        >
          <option value="all">All repositories</option>
          {repositories.map((repo) => (
            <option key={repo} value={repo}>
              {repo}
            </option>
          ))}
        </select>
        <select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value as EventTypeFilter)}
          aria-label="Filter by event type"
        >
          <option value="all">All event types</option>
          <option value="STATE_TRANSITION">State transitions</option>
          <option value="APPROVAL_DECISION">Approval decisions</option>
          <option value="REPOSITORY_MUTATION">Repository mutations</option>
        </select>
      </div>

      {error && <p className="error">{error}</p>}

      {entries && filtered.length === 0 && (
        <section className="card">
          <p className="hint">{entries.length === 0 ? 'No audit entries recorded yet.' : 'No entries match the current filter.'}</p>
        </section>
      )}

      {filtered.length > 0 && (
        <section className="card table-card">
          <table className="run-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Repository</th>
                <th>Run</th>
                <th>Type</th>
                <th>Detail</th>
                <th>Principal</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((entry) => (
                <tr key={entry.id}>
                  <td className="muted nowrap">{formatDateTime(entry.occurredAt)}</td>
                  <td className="muted">{entry.repositoryId}</td>
                  <td>
                    <Link to={`/runs/${entry.runId}`} className="run-table-name">
                      <code>{shortHash(entry.runId)}</code>
                    </Link>
                  </td>
                  <td>
                    <span className={`audit-kind kind-${entry.eventType.toLowerCase()}`}>
                      {EVENT_TYPE_LABELS[entry.eventType] ?? entry.eventType}
                    </span>
                  </td>
                  <td>
                    {entry.detail}
                    {entry.planHash && <code className="audit-hash">{shortHash(entry.planHash)}</code>}
                  </td>
                  <td className="muted">{entry.principal}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}
