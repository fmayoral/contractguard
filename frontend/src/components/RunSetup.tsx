import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { api, ApiError } from '../api';
import { isTerminal, stateTone } from '../format';
import { useLiveRuns } from '../useLiveRuns';
import { Badge } from './Badge';
import { DiffPreview } from './DiffPreview';
import type { SetupOptions, SpecOption } from '../types';

interface RunSetupProps {
  onCreated: (runId: string) => void;
}

/** Carried over by a "Run again" click (RunDetailPage) via navigation state -- the same
 * mechanism action-required toasts use to carry a scroll target. */
interface RunAgainState {
  repositoryId?: string;
  oldSpec?: string;
  newSpec?: string;
}

/**
 * A single, focused panel: pick a repository and two specs, name the run, start it. Registering
 * repositories/specs is a deliberately separate concern (the Settings page) rather than inline
 * here, so this form never grows past "make three choices and go" (FR-044).
 */
export function RunSetup({ onCreated }: RunSetupProps) {
  const carriedOver = useLocation().state as RunAgainState | null;
  const [options, setOptions] = useState<SetupOptions | null>(null);
  const [name, setName] = useState('');
  const [repository, setRepository] = useState(carriedOver?.repositoryId ?? '');
  const [oldSpec, setOldSpec] = useState(carriedOver?.oldSpec ?? '');
  const [newSpec, setNewSpec] = useState(carriedOver?.newSpec ?? '');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { runs: liveRuns } = useLiveRuns();

  // Mirrors the backend's per-repository exclusivity (RunService.createRun's REPOSITORY_BUSY
  // check): proactively blocking here prevents the race the reactive error would otherwise
  // only report after the fact. Scoped to the selected repository only -- the backend allows
  // concurrent runs on different repositories, so this must not block those too.
  const blockingRun = repository
    ? liveRuns.find((run) => run.repositoryId === repository && !isTerminal(run.state))
    : undefined;

  const refreshOptions = () =>
    api.setup().then((setup) => {
      setOptions(setup);
      const knownRepositories = new Set([
        ...setup.repositories,
        ...setup.remoteRepositories.map((r) => r.repositoryId),
      ]);
      const knownSpecs = new Set(setup.specifications.map((s) => s.id));
      // Fills any still-empty *or* no-longer-valid slot with a default -- the latter covers a
      // "Run again" carrying over a spec whose source was deregistered since -- but never
      // overwrites a choice that's still good, whether the user made it or "Run again" did.
      setRepository((current) =>
        current && knownRepositories.has(current)
          ? current
          : setup.repositories[0] || setup.remoteRepositories[0]?.repositoryId || '');
      setOldSpec((current) => (current && knownSpecs.has(current) ? current : setup.specifications[0]?.id || ''));
      setNewSpec((current) =>
        current && knownSpecs.has(current)
          ? current
          : setup.specifications[1]?.id || setup.specifications[0]?.id || '');
      return setup;
    });

  useEffect(() => {
    refreshOptions().catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const run = await api.createRun(name, repository, oldSpec, newSpec);
      onCreated(run.id);
    } catch (e) {
      setError(e instanceof ApiError && e.remediation ? `${e.message} — ${e.remediation}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  if (!options) {
    return <section className="card">{error ?? 'Loading setup…'}</section>;
  }

  const noRepositories = options.repositories.length === 0 && options.remoteRepositories.length === 0;
  const noSpecs = options.specifications.length === 0;

  const local = options.specifications.filter((o) => o.origin === 'local');
  const uploaded = options.specifications.filter((o) => o.origin === 'uploaded');
  const bySource = new Map<string, SpecOption[]>();
  for (const option of options.specifications) {
    if (option.origin !== 'spec_source' || !option.sourceId) continue;
    const group = bySource.get(option.sourceId) ?? [];
    group.push(option);
    bySource.set(option.sourceId, group);
  }
  const specGroups = (
    <>
      {local.length > 0 && (
        <optgroup label="Local workspace">
          {local.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
        </optgroup>
      )}
      {uploaded.length > 0 && (
        <optgroup label="Uploaded">
          {uploaded.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
        </optgroup>
      )}
      {[...bySource.entries()].map(([sourceId, sourceOptions]) => (
        <optgroup key={sourceId} label={`From ${sourceId}`}>
          {sourceOptions.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
        </optgroup>
      ))}
    </>
  );

  return (
    <section className="card">
      <div className="card-header-row">
        <h2>New analysis run</h2>
        <Link className="secondary small button-like" to="/settings">
          Manage sources
        </Link>
      </div>
      <div className="form-grid">
        <label>
          Run name
          <input
            value={name}
            placeholder="optional"
            disabled={!!blockingRun}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          Consumer repository
          <select value={repository} onChange={(e) => setRepository(e.target.value)}>
            {options.repositories.length > 0 && (
              <optgroup label="Local workspace">
                {options.repositories.map((repo) => <option key={repo}>{repo}</option>)}
              </optgroup>
            )}
            {options.remoteRepositories.length > 0 && (
              <optgroup label="Registered GitHub repositories">
                {options.remoteRepositories.map((repo) => (
                  <option key={repo.repositoryId} value={repo.repositoryId}>{repo.repositoryId}</option>
                ))}
              </optgroup>
            )}
          </select>
        </label>
        <label>
          Old specification
          <select value={oldSpec} disabled={!!blockingRun} onChange={(e) => setOldSpec(e.target.value)}>
            {specGroups}
          </select>
        </label>
        <label>
          New specification
          <select value={newSpec} disabled={!!blockingRun} onChange={(e) => setNewSpec(e.target.value)}>
            {specGroups}
          </select>
        </label>
      </div>

      {oldSpec && newSpec && <DiffPreview oldSpec={oldSpec} newSpec={newSpec} />}

      {blockingRun && (
        <p className="hint busy-hint">
          <strong>{blockingRun.name}</strong> is already running on this repository (currently{' '}
          <Badge tone={stateTone(blockingRun.state)}>{blockingRun.state}</Badge>). Wait for it to
          finish, or <Link to={`/runs/${blockingRun.id}`}>watch its progress</Link>. You can still
          pick a different repository above.
        </p>
      )}

      {noRepositories && (
        <p className="hint">
          No repositories available yet. Press <strong>Manage sources</strong> above to register one,
          or run <code>scripts/reset-demo</code> to materialise the bundled consumer.
        </p>
      )}
      {!noRepositories && noSpecs && (
        <p className="hint">
          No specifications available yet. Press <strong>Manage sources</strong> above to upload one
          or register a spec repository.
        </p>
      )}

      <button disabled={busy || !repository || !oldSpec || !newSpec || !!blockingRun} onClick={start}>
        {busy ? 'Starting…' : 'Start analysis'}
      </button>
      {error && <p className="error">{error}</p>}
    </section>
  );
}
