import { useState } from 'react';
import { api, ApiError } from '../api';

interface RegisterSpecSourceProps {
  onRegistered: (repositoryId: string) => void;
}

/** Collapsed by default, mirroring RegisterRemoteRepository (FR-043, ADR-0012). Token is optional. */
export function RegisterSpecSource({ onRegistered }: RegisterSpecSourceProps) {
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
      const registered = await api.registerSpecSource(repositoryId, cloneUrl, defaultBranch, token);
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
      <summary>+ Register a spec repository</summary>
      <div className="form-grid">
        <label>
          Repository ID
          <input
            value={repositoryId}
            placeholder="openapi-specs"
            onChange={(e) => setRepositoryId(e.target.value)}
          />
        </label>
        <label>
          Clone URL
          <input
            value={cloneUrl}
            placeholder="https://github.com/acme/openapi-specs"
            onChange={(e) => setCloneUrl(e.target.value)}
          />
        </label>
        <label>
          Default branch
          <input value={defaultBranch} onChange={(e) => setDefaultBranch(e.target.value)} />
        </label>
        <label>
          Personal access token (optional — only needed for a private repository)
          <input type="password" value={token} onChange={(e) => setToken(e.target.value)} />
        </label>
      </div>
      <button disabled={busy || !repositoryId || !cloneUrl || !defaultBranch} onClick={register}>
        {busy ? 'Registering…' : 'Register spec repository'}
      </button>
      <p className="hint">
        Public repositories need no token. A stored token is encrypted at rest (AES-256-GCM) and
        never shown again — see ADR-0012.
      </p>
      {error && <p className="error">{error}</p>}
    </details>
  );
}
