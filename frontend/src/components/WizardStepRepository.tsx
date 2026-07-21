import { RegisterRemoteRepository } from './RegisterRemoteRepository';
import type { SetupOptions } from '../types';

interface WizardStepRepositoryProps {
  options: SetupOptions;
  repository: string;
  onChange: (repositoryId: string) => void;
  onRepositoryRegistered: (repositoryId: string) => void;
}

/** Step 1 of the run-setup wizard: which consumer repository to analyse (FR-043). */
export function WizardStepRepository({
  options,
  repository,
  onChange,
  onRepositoryRegistered,
}: WizardStepRepositoryProps) {
  const noRepositories = options.repositories.length === 0 && options.remoteRepositories.length === 0;

  return (
    <div>
      <label>
        Consumer repository
        <select value={repository} onChange={(e) => onChange(e.target.value)}>
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
      {noRepositories && (
        <p className="hint">
          No repositories found. Run <code>scripts/reset-demo</code> to materialise the bundled consumer,
          or register a GitHub repository below.
        </p>
      )}
      <RegisterRemoteRepository onRegistered={onRepositoryRegistered} />
    </div>
  );
}
