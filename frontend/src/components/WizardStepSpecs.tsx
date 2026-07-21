import { RegisterSpecSource } from './RegisterSpecSource';
import { UploadSpecification } from './UploadSpecification';
import type { SetupOptions, SpecOption } from '../types';

interface WizardStepSpecsProps {
  options: SetupOptions;
  oldSpec: string;
  newSpec: string;
  onOldSpecChange: (id: string) => void;
  onNewSpecChange: (id: string) => void;
  onOptionsChanged: (selectId?: string) => void;
}

/** Step 2 of the run-setup wizard: old/new specifications, from any origin (FR-043, ADR-0012). */
export function WizardStepSpecs({
  options,
  oldSpec,
  newSpec,
  onOldSpecChange,
  onNewSpecChange,
  onOptionsChanged,
}: WizardStepSpecsProps) {
  const local = options.specifications.filter((o) => o.origin === 'local');
  const uploaded = options.specifications.filter((o) => o.origin === 'uploaded');
  const bySource = new Map<string, SpecOption[]>();
  for (const option of options.specifications) {
    if (option.origin !== 'spec_source' || !option.sourceId) continue;
    const group = bySource.get(option.sourceId) ?? [];
    group.push(option);
    bySource.set(option.sourceId, group);
  }

  const groups = (
    <>
      {local.length > 0 && (
        <optgroup label="Local workspace">
          {local.map((o) => (
            <option key={o.id} value={o.id}>{o.label}</option>
          ))}
        </optgroup>
      )}
      {uploaded.length > 0 && (
        <optgroup label="Uploaded">
          {uploaded.map((o) => (
            <option key={o.id} value={o.id}>{o.label}</option>
          ))}
        </optgroup>
      )}
      {[...bySource.entries()].map(([sourceId, sourceOptions]) => (
        <optgroup key={sourceId} label={`From ${sourceId}`}>
          {sourceOptions.map((o) => (
            <option key={o.id} value={o.id}>{o.label}</option>
          ))}
        </optgroup>
      ))}
    </>
  );

  const noSpecs = options.specifications.length === 0;

  return (
    <div>
      <div className="form-grid">
        <label>
          Old specification
          <select value={oldSpec} onChange={(e) => onOldSpecChange(e.target.value)}>
            {groups}
          </select>
        </label>
        <label>
          New specification
          <select value={newSpec} onChange={(e) => onNewSpecChange(e.target.value)}>
            {groups}
          </select>
        </label>
      </div>
      {noSpecs && (
        <p className="hint">
          No specifications available yet. Upload one below or register a spec repository.
        </p>
      )}
      {options.specSources.length > 0 && (
        <p className="hint">
          Registered spec repositories:{' '}
          {options.specSources.map((s) => `${s.repositoryId} (${s.owner}/${s.name})`).join(', ')}
          {bySource.size < options.specSources.length && (
            <>
              {' '}
              — {options.specSources.length - bySource.size} contributed no files (clone may have
              failed; check the token and default branch).
            </>
          )}
        </p>
      )}
      <UploadSpecification onUploaded={(specId) => onOptionsChanged(specId)} />
      <RegisterSpecSource onRegistered={() => onOptionsChanged()} />
    </div>
  );
}
