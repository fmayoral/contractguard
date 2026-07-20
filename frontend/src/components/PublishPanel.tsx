import { useState } from 'react';
import { api, ApiError } from '../api';

interface PublishPanelProps {
  runId: string;
  state: string;
  remoteRepository: boolean;
  pullRequestUrl: string | null;
  onChanged: () => void;
}

const PUBLISH_RELEVANT_STATES = ['SUCCEEDED', 'PUBLISHING', 'PUBLISHED', 'PUBLISH_FAILED'];

/** Pushes the validated remediation branch and opens a draft pull request — a separate, explicit
 * action from Execute, never automatic (FR-027). */
export function PublishPanel({ runId, state, remoteRepository, pullRequestUrl, onChanged }: PublishPanelProps) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!PUBLISH_RELEVANT_STATES.includes(state)) {
    return <p className="hint">Publishing becomes available once remediation succeeds.</p>;
  }
  if (!remoteRepository) {
    return (
      <p className="hint">
        This repository was not registered for remote publishing, so there is nothing to push —
        register it above before starting a run to enable this.
      </p>
    );
  }

  const publish = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.publish(runId);
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError && e.remediation ? `${e.message} — ${e.remediation}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  const canPublish = state === 'SUCCEEDED' || state === 'PUBLISH_FAILED';

  return (
    <div className="publish-panel">
      {canPublish && (
        <button disabled={busy} onClick={publish}>
          {busy ? 'Publishing…' : state === 'PUBLISH_FAILED' ? 'Retry publish' : 'Publish (push & open draft PR)'}
        </button>
      )}
      {state === 'PUBLISHING' && <p className="hint">Pushing the branch and opening the pull request…</p>}
      {state === 'PUBLISH_FAILED' && (
        <p className="error">Publish failed — see the timeline for details, then retry once fixed.</p>
      )}
      {state === 'PUBLISHED' && pullRequestUrl && (
        <p>
          Draft pull request:{' '}
          <a href={pullRequestUrl} target="_blank" rel="noreferrer">
            {pullRequestUrl}
          </a>
        </p>
      )}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
