import { useRef, useState } from 'react';
import { api, ApiError } from '../api';

interface UploadSpecificationProps {
  onUploaded: (specId: string) => void;
}

/** Manual upload for anyone with spec files but no repository for them (FR-043, ADR-0012). */
export function UploadSpecification({ onUploaded }: UploadSpecificationProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const upload = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      const uploaded = await api.uploadSpecification(file);
      onUploaded(uploaded.id);
    } catch (e) {
      setError(e instanceof ApiError && e.remediation ? `${e.message} — ${e.remediation}` : String(e));
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = '';
    }
  };

  return (
    <div className="upload-spec">
      <label>
        Upload a specification file
        <input
          ref={inputRef}
          type="file"
          accept=".yaml,.yml,.json"
          disabled={busy}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void upload(file);
          }}
        />
      </label>
      <p className="hint">
        Upload the current (old) spec and the proposed (new) spec separately — each becomes
        selectable below once uploaded. Re-uploading the same file name replaces it.
      </p>
      {busy && <p className="hint">Uploading…</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
