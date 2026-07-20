import { useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { RegisterRemoteRepository } from './RegisterRemoteRepository';
import type { SetupOptions } from '../types';

interface RunSetupProps {
  onCreated: (runId: string) => void;
}

export function RunSetup({ onCreated }: RunSetupProps) {
  const [options, setOptions] = useState<SetupOptions | null>(null);
  const [name, setName] = useState('');
  const [repository, setRepository] = useState('');
  const [oldSpec, setOldSpec] = useState('');
  const [newSpec, setNewSpec] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const loadOptions = () => api.setup().then((setup) => {
    setOptions(setup);
    return setup;
  });

  useEffect(() => {
    loadOptions()
      .then((setup) => {
        setRepository(setup.repositories[0] ?? setup.remoteRepositories[0] ?? '');
        setOldSpec(setup.specifications[0] ?? '');
        setNewSpec(setup.specifications[1] ?? setup.specifications[0] ?? '');
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  const onRepositoryRegistered = (repositoryId: string) => {
    loadOptions()
      .then(() => setRepository(repositoryId))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
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

  return (
    <section className="card">
      <h2>New analysis run</h2>
      <div className="form-grid">
        <label>
          Run name
          <input
            value={name}
            placeholder="optional"
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          Consumer repository
          <select value={repository} onChange={(e) => setRepository(e.target.value)}>
            {options.repositories.length > 0 && (
              <optgroup label="Local workspace">
                {options.repositories.map((repo) => (
                  <option key={repo}>{repo}</option>
                ))}
              </optgroup>
            )}
            {options.remoteRepositories.length > 0 && (
              <optgroup label="Registered GitHub repositories">
                {options.remoteRepositories.map((repo) => (
                  <option key={repo}>{repo}</option>
                ))}
              </optgroup>
            )}
          </select>
        </label>
        <label>
          Old specification
          <select value={oldSpec} onChange={(e) => setOldSpec(e.target.value)}>
            {options.specifications.map((spec) => (
              <option key={spec}>{spec}</option>
            ))}
          </select>
        </label>
        <label>
          New specification
          <select value={newSpec} onChange={(e) => setNewSpec(e.target.value)}>
            {options.specifications.map((spec) => (
              <option key={spec}>{spec}</option>
            ))}
          </select>
        </label>
      </div>
      <button disabled={busy || !repository || !oldSpec || !newSpec} onClick={start}>
        {busy ? 'Starting…' : 'Start analysis'}
      </button>
      {noRepositories && (
        <p className="hint">
          No repositories found. Run <code>scripts/reset-demo</code> to materialise the bundled consumer,
          or register a GitHub repository below.
        </p>
      )}
      {error && <p className="error">{error}</p>}

      <RegisterRemoteRepository onRegistered={onRepositoryRegistered} />
    </section>
  );
}
