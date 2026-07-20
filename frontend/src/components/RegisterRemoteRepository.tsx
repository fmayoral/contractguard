import { useState } from 'react';
import { api, ApiError } from '../api';

interface RegisterRemoteRepositoryProps {
  onRegistered: (repositoryId: string) => void;
}

/** Collapsed by default: registering a remote repo is occasional, not the primary flow (FR-027). */
export function RegisterRemoteRepository({ onRegistered }: RegisterRemoteRepositoryProps) {
  const [open, setOpen] = useState(false);
  const [repositoryId, setRepositoryId] = useState('');
  const [cloneUrl, setCloneUrl] = useState('');
  const [defaultBranch, setDefaultBranch] = useState('main');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const register = async () => {
    setBusy(true);
    setError(null);
    try {
      const registered = await api.registerRemoteRepository(repositoryId, cloneUrl, defaultBranch, token);
      setRepositoryId('');
      setCloneUrl('');
      setDefaultBranch('main');
      setToken('');
      setOpen(false);
      onRegistered(registered.repositoryId);
    } catch (e) {
      setError(e instanceof ApiError && e.remediation ? `${e.message} — ${e.remediation}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <details
      className="register-remote"
      open={open}
      onToggle={(e) => setOpen(e.currentTarget.open)}
    >
      <summary>+ Register a GitHub repository</summary>
      <div className="form-grid">
        <label>
          Repository ID
          <input
            value={repositoryId}
            placeholder="acme-widgets"
            onChange={(e) => setRepositoryId(e.target.value)}
          />
        </label>
        <label>
          Clone URL
          <input
            value={cloneUrl}
            placeholder="https://github.com/acme/widgets"
            onChange={(e) => setCloneUrl(e.target.value)}
          />
        </label>
        <label>
          Default branch
          <input value={defaultBranch} onChange={(e) => setDefaultBranch(e.target.value)} />
        </label>
        <label>
          Personal access token (repo scope)
          <input type="password" value={token} onChange={(e) => setToken(e.target.value)} />
        </label>
      </div>
      <button
        disabled={busy || !repositoryId || !cloneUrl || !defaultBranch || !token}
        onClick={register}
      >
        {busy ? 'Registering…' : 'Register repository'}
      </button>
      <p className="hint">
        The token is encrypted at rest (AES-256-GCM) and never shown again — see ADR-0007.
      </p>
      {error && <p className="error">{error}</p>}
    </details>
  );
}
