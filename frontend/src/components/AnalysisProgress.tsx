import { useEffect, useState } from 'react';

const ANALYSIS_STEPS = [
  { state: 'VALIDATING_INPUT', label: 'Validate' },
  { state: 'DIFFING', label: 'Compare specs' },
  { state: 'SEARCHING', label: 'Search evidence' },
  { state: 'ASSESSING', label: 'Assess impact' },
  { state: 'PLANNING', label: 'Draft plan' },
];

const EXECUTION_STEPS = [
  { state: 'PREPARING_BRANCH', label: 'Prepare branch' },
  { state: 'PATCHING', label: 'Apply patch' },
  { state: 'VALIDATING', label: 'Build & test' },
  { state: 'REPAIRING', label: 'Repair & retry' },
];

const STEP_LABELS: Record<string, string> = {
  CREATED: 'Queued',
  VALIDATING_INPUT: 'Validating input',
  DIFFING: 'Comparing specifications',
  SEARCHING: 'Searching consumer code for evidence',
  ASSESSING: 'Assessing consumer impact',
  PLANNING: 'Drafting migration plan',
  PREPARING_BRANCH: 'Preparing an isolated branch',
  PATCHING: 'Applying the remediation patch',
  VALIDATING: 'Running the consumer build & tests',
  REPAIRING: 'Repairing a failed validation',
};

function formatElapsed(millis: number): string {
  const totalSeconds = Math.max(0, Math.floor(millis / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

function useElapsed(since: string): number {
  const [elapsed, setElapsed] = useState(() => Date.now() - new Date(since).getTime());
  useEffect(() => {
    setElapsed(Date.now() - new Date(since).getTime());
    const id = setInterval(() => setElapsed(Date.now() - new Date(since).getTime()), 1000);
    return () => clearInterval(id);
  }, [since]);
  return elapsed;
}

interface AnalysisProgressProps {
  state: string;
  since: string;
}

/**
 * A live "the platform is working" indicator for the run's active pipeline
 * steps -- only rendered while genuinely busy (App gates on stateTone ===
 * 'busy'), never during AWAITING_APPROVAL, which is waiting on the human,
 * not the system.
 */
export function AnalysisProgress({ state, since }: AnalysisProgressProps) {
  const elapsed = useElapsed(since);
  const isExecution = EXECUTION_STEPS.some((step) => step.state === state);
  const steps = isExecution ? EXECUTION_STEPS : ANALYSIS_STEPS;
  const currentIndex = steps.findIndex((step) => step.state === state);

  return (
    <section className="card progress-card" aria-live="polite">
      <div className="progress-headline">
        <span className="progress-dot" aria-hidden="true" />
        <span className="progress-label">{STEP_LABELS[state] ?? 'Working'}…</span>
        <span className="progress-elapsed">{formatElapsed(elapsed)}</span>
      </div>
      <div className="progress-bar" aria-hidden="true">
        <div className="progress-bar-fill" />
      </div>
      <ol className="progress-steps">
        {steps.map((step, index) => (
          <li key={step.state} className={index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'pending'}>
            {step.label}
          </li>
        ))}
      </ol>
    </section>
  );
}
