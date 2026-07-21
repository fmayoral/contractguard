import { useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { ManageSourcesModal } from './ManageSourcesModal';
import type { SetupOptions, SpecOption } from '../types';

interface RunSetupProps {
  onCreated: (runId: string) => void;
}

/**
 * A single, focused panel: pick a repository and two specs, name the run, start it. Registering
 * repositories/specs is a deliberately separate action (the "Manage sources" modal) rather than
 * inline here, so this form never grows past "make three choices and go" (FR-044).
 */
export function RunSetup({ onCreated }: RunSetupProps) {
  const [options, setOptions] = useState<SetupOptions | null>(null);
  const [name, setName] = useState('');
  const [repository, setRepository] = useState('');
  const [oldSpec, setOldSpec] = useState('');
  const [newSpec, setNewSpec] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [manageOpen, setManageOpen] = useState(false);

  const refreshOptions = () =>
    api.setup().then((setup) => {
      setOptions(setup);
      // Fills any still-empty slot with a default; never overwrites a choice already made.
      setRepository((current) => current || setup.repositories[0] || setup.remoteRepositories[0]?.repositoryId || '');
      setOldSpec((current) => current || setup.specifications[0]?.id || '');
      setNewSpec((current) => current || setup.specifications[1]?.id || setup.specifications[0]?.id || '');
      return setup;
    });

  useEffect(() => {
    refreshOptions().catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onSourcesChanged = () => {
    refreshOptions().catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  };

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
        <button type="button" className="secondary small" onClick={() => setManageOpen(true)}>
          Manage sources
        </button>
      </div>
      <div className="form-grid">
        <label>
          Run name
          <input value={name} placeholder="optional" onChange={(e) => setName(e.target.value)} />
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
          <select value={oldSpec} onChange={(e) => setOldSpec(e.target.value)}>
            {specGroups}
          </select>
        </label>
        <label>
          New specification
          <select value={newSpec} onChange={(e) => setNewSpec(e.target.value)}>
            {specGroups}
          </select>
        </label>
      </div>

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

      <button disabled={busy || !repository || !oldSpec || !newSpec} onClick={start}>
        {busy ? 'Starting…' : 'Start analysis'}
      </button>
      {error && <p className="error">{error}</p>}

      {manageOpen && (
        <ManageSourcesModal
          options={options}
          onClose={() => setManageOpen(false)}
          onChanged={onSourcesChanged}
        />
      )}
    </section>
  );
}
