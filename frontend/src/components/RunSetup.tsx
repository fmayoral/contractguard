import { useEffect, useState } from 'react';
import { api, ApiError } from '../api';
import { WizardStepRepository } from './WizardStepRepository';
import { WizardStepSpecs } from './WizardStepSpecs';
import type { SetupOptions } from '../types';

interface RunSetupProps {
  onCreated: (runId: string) => void;
}

const STEP_TITLES = ['Consumer repository', 'Specifications', 'Review & start'];

/** A 3-step wizard: which repository, which old/new specs, then name and start (FR-043). */
export function RunSetup({ onCreated }: RunSetupProps) {
  const [options, setOptions] = useState<SetupOptions | null>(null);
  const [step, setStep] = useState(1);
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
        setOldSpec(setup.specifications[0]?.id ?? '');
        setNewSpec(setup.specifications[1]?.id ?? setup.specifications[0]?.id ?? '');
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  const onRepositoryRegistered = (repositoryId: string) => {
    loadOptions()
      .then(() => setRepository(repositoryId))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  };

  /** @param justAddedId if given, fills the first still-empty spec slot with it; never overwrites a choice. */
  const onSpecOptionsChanged = (justAddedId?: string) => {
    loadOptions()
      .then(() => {
        if (!justAddedId) return;
        if (!oldSpec) {
          setOldSpec(justAddedId);
        } else if (!newSpec) {
          setNewSpec(justAddedId);
        }
      })
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

  const canAdvanceFromStep1 = repository !== '';
  const canAdvanceFromStep2 = oldSpec !== '' && newSpec !== '';
  const oldSpecLabel = options.specifications.find((o) => o.id === oldSpec)?.label ?? oldSpec;
  const newSpecLabel = options.specifications.find((o) => o.id === newSpec)?.label ?? newSpec;

  return (
    <section className="card">
      <h2>New analysis run</h2>
      <ol className="wizard-steps">
        {STEP_TITLES.map((title, index) => {
          const stepNumber = index + 1;
          return (
            <li
              key={title}
              className={
                stepNumber === step ? 'active' : stepNumber < step ? 'done' : undefined
              }
            >
              {stepNumber}. {title}
            </li>
          );
        })}
      </ol>

      {step === 1 && (
        <WizardStepRepository
          options={options}
          repository={repository}
          onChange={setRepository}
          onRepositoryRegistered={onRepositoryRegistered}
        />
      )}
      {step === 2 && (
        <WizardStepSpecs
          options={options}
          oldSpec={oldSpec}
          newSpec={newSpec}
          onOldSpecChange={setOldSpec}
          onNewSpecChange={setNewSpec}
          onOptionsChanged={onSpecOptionsChanged}
        />
      )}
      {step === 3 && (
        <div className="form-grid">
          <label>
            Run name
            <input value={name} placeholder="optional" onChange={(e) => setName(e.target.value)} />
          </label>
          <p className="hint">
            Repository: <strong>{repository}</strong>
            <br />
            Old specification: <strong>{oldSpecLabel}</strong>
            <br />
            New specification: <strong>{newSpecLabel}</strong>
          </p>
        </div>
      )}

      <div className="wizard-nav">
        {step > 1 && (
          <button type="button" className="secondary" onClick={() => setStep((s) => s - 1)}>
            Back
          </button>
        )}
        {step < 3 && (
          <button
            type="button"
            disabled={(step === 1 && !canAdvanceFromStep1) || (step === 2 && !canAdvanceFromStep2)}
            onClick={() => setStep((s) => s + 1)}
          >
            Next
          </button>
        )}
        {step === 3 && (
          <button disabled={busy || !repository || !oldSpec || !newSpec} onClick={start}>
            {busy ? 'Starting…' : 'Start analysis'}
          </button>
        )}
      </div>
      {error && <p className="error">{error}</p>}
    </section>
  );
}
