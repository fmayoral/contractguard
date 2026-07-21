import { useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { RegisterRemoteRepository } from './RegisterRemoteRepository';
import { RegisterSpecSource } from './RegisterSpecSource';
import { UploadSpecification } from './UploadSpecification';
import type { SetupOptions } from '../types';

interface ManageSourcesModalProps {
  options: SetupOptions;
  onClose: () => void;
  onChanged: () => void;
}

/**
 * Registering, uploading and removing repositories/specs, kept entirely separate from starting a
 * run (FR-044): opened deliberately, closed deliberately, never mixed into the run-creation flow.
 */
export function ManageSourcesModal({ options, onClose, onChanged }: ManageSourcesModalProps) {
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const runRemoval = async (label: string, action: () => Promise<void>) => {
    setError(null);
    try {
      await action();
      onChanged();
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

  const uploaded = options.specifications.filter((o) => o.origin === 'uploaded');

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <header className="modal-header">
          <h2>Manage repositories &amp; specifications</h2>
          <button type="button" className="modal-close" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </header>

        {error && <p className="error">{error}</p>}

        <section className="modal-section">
          <h3>Consumer repositories</h3>
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
          <RegisterRemoteRepository onRegistered={onChanged} />
        </section>

        <section className="modal-section">
          <h3>Specification repositories</h3>
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
          <RegisterSpecSource onRegistered={onChanged} />
        </section>

        <section className="modal-section">
          <h3>Uploaded specifications</h3>
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
          <UploadSpecification onUploaded={onChanged} />
        </section>

        <div className="modal-footer">
          <button type="button" onClick={onClose}>Done</button>
        </div>
      </div>
    </div>
  );
}
