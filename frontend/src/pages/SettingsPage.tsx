import { useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { applyTheme, initialTheme, storeTheme, type Theme } from '../theme';
import { RegisterRemoteRepository } from '../components/RegisterRemoteRepository';
import { RegisterSpecSource } from '../components/RegisterSpecSource';
import { UploadSpecification } from '../components/UploadSpecification';
import { HelpModal } from '../components/HelpModal';
import type { SetupOptions } from '../types';

/**
 * Everything that isn't a run: source registration/removal (the FR-044 manage-sources
 * surface, now a page instead of a modal), appearance, and the quick reference.
 */
export function SettingsPage() {
  const [options, setOptions] = useState<SetupOptions | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const [helpOpen, setHelpOpen] = useState(false);

  const refresh = () => {
    api
      .setup()
      .then((setup) => {
        setOptions(setup);
        setError(null);
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(refresh, []);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  const chooseTheme = (next: Theme) => {
    setTheme(next);
    storeTheme(next);
  };

  const runRemoval = async (label: string, action: () => Promise<void>) => {
    setError(null);
    try {
      await action();
      refresh();
    } catch (e) {
      setError(e instanceof ApiError && e.remediation
        ? `Could not remove ${label}: ${e.message} — ${e.remediation}`
        : `Could not remove ${label}: ${String(e)}`);
    }
  };

  const removeRemoteRepository = (repositoryId: string) => {
    if (!window.confirm(`Remove registered repository "${repositoryId}"? Its stored token is deleted too.`)) return;
    void runRemoval(repositoryId, () => api.deregisterRemoteRepository(repositoryId));
  };

  const removeSpecSource = (repositoryId: string) => {
    if (!window.confirm(`Remove spec repository "${repositoryId}"?`)) return;
    void runRemoval(repositoryId, () => api.deregisterSpecSource(repositoryId));
  };

  const removeUpload = (fileName: string) => {
    if (!window.confirm(`Remove uploaded file "${fileName}"?`)) return;
    void runRemoval(fileName, () => api.deleteUploadedSpecification(fileName));
  };

  const uploaded = options?.specifications.filter((o) => o.origin === 'uploaded') ?? [];

  return (
    <div className="page narrow">
      <header className="page-header">
        <h2>Settings</h2>
        <p className="muted">Sources, appearance and reference.</p>
      </header>

      {error && <p className="error">{error}</p>}

      <section className="card">
        <h3>Consumer repositories</h3>
        <p className="muted">The codebases ContractGuard analyses and remediates.</p>
        {options ? (
          <>
            {options.repositories.length > 0 && (
              <ul className="source-list">
                {options.repositories.map((repo) => (
                  <li key={repo}>
                    <span>{repo}</span>
                    <span className="hint">local workspace</span>
                  </li>
                ))}
              </ul>
            )}
            {options.remoteRepositories.length > 0 && (
              <ul className="source-list">
                {options.remoteRepositories.map((repo) => (
                  <li key={repo.repositoryId}>
                    <span>
                      {repo.repositoryId} <span className="muted">({repo.owner}/{repo.name})</span>
                    </span>
                    <button type="button" className="secondary" onClick={() => removeRemoteRepository(repo.repositoryId)}>
                      Remove
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {options.repositories.length === 0 && options.remoteRepositories.length === 0 && (
              <p className="hint">No repositories yet — register one below, or run scripts/reset-demo.</p>
            )}
            <RegisterRemoteRepository onRegistered={refresh} />
          </>
        ) : (
          <p className="hint">Loading…</p>
        )}
      </section>

      <section className="card">
        <h3>Specification repositories</h3>
        <p className="muted">Read-only repositories offering OpenAPI files; a token is only needed for private ones.</p>
        {options && (
          <>
            {options.specSources.length > 0 ? (
              <ul className="source-list">
                {options.specSources.map((source) => (
                  <li key={source.repositoryId}>
                    <span>
                      {source.repositoryId} <span className="muted">({source.owner}/{source.name})</span>
                    </span>
                    <button type="button" className="secondary" onClick={() => removeSpecSource(source.repositoryId)}>
                      Remove
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="hint">No spec repositories registered yet.</p>
            )}
            <RegisterSpecSource onRegistered={refresh} />
          </>
        )}
      </section>

      <section className="card">
        <h3>Uploaded specifications</h3>
        <p className="muted">Individual OpenAPI files; re-uploading the same name replaces it.</p>
        {options && (
          <>
            {uploaded.length > 0 ? (
              <ul className="source-list">
                {uploaded.map((option) => (
                  <li key={option.id}>
                    <span>{option.label}</span>
                    <button type="button" className="secondary" onClick={() => removeUpload(option.label)}>
                      Remove
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="hint">No files uploaded yet.</p>
            )}
            <UploadSpecification onUploaded={refresh} />
          </>
        )}
      </section>

      <section className="card">
        <h3>Appearance</h3>
        <div className="theme-choice" role="radiogroup" aria-label="Theme">
          <label>
            <input type="radio" name="theme" checked={theme === 'dark'} onChange={() => chooseTheme('dark')} />
            Dark
          </label>
          <label>
            <input type="radio" name="theme" checked={theme === 'light'} onChange={() => chooseTheme('light')} />
            Light
          </label>
        </div>
      </section>

      <section className="card">
        <h3>Reference</h3>
        <p className="muted">The workflow, run states and safety guarantees in one page.</p>
        <button type="button" className="secondary" onClick={() => setHelpOpen(true)}>
          Open quick reference
        </button>
        {helpOpen && <HelpModal onClose={() => setHelpOpen(false)} />}
      </section>
    </div>
  );
}
