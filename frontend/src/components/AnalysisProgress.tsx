import { useEffect, useRef, useState } from 'react';
import { isTerminal, stateTone } from '../format';
import type { RunEvent } from '../types';
import { Timeline } from './Timeline';

// One combined pipeline, in order, covering the run's whole lifecycle --
// including publish, since that is a real (optional) later stage, not a
// separate concern. A step from an earlier phase stays in the list (and
// marked done) once the run moves on, instead of being swapped out or
// disappearing.
const PIPELINE_STEPS = [
  { state: 'VALIDATING_INPUT', label: 'Validate' },
  { state: 'DIFFING', label: 'Compare specs' },
  { state: 'SEARCHING', label: 'Search evidence' },
  { state: 'ASSESSING', label: 'Assess impact' },
  { state: 'PLANNING', label: 'Draft plan' },
  { state: 'PREPARING_BRANCH', label: 'Prepare branch' },
  { state: 'PATCHING', label: 'Apply patch' },
  { state: 'VALIDATING', label: 'Build & test' },
  { state: 'REPAIRING', label: 'Repair & retry' },
  { state: 'PUBLISHING', label: 'Publish' },
];

const ANALYSIS_DONE_BOUNDARY = PIPELINE_STEPS.findIndex((step) => step.state === 'PLANNING') + 1;
const PUBLISH_INDEX = PIPELINE_STEPS.length - 1;

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
  PUBLISHING: 'Publishing the pull request',
};

const OUTCOME_LABELS: Record<string, string> = {
  AWAITING_APPROVAL: 'Waiting for your approval',
  REJECTED: 'Plan rejected',
  CANCELLED: 'Run cancelled',
  SUCCEEDED: 'Analysis succeeded',
  FAILED: 'Run failed',
  PUBLISHED: 'Published',
  PUBLISH_FAILED: 'Publish failed',
};

/** How many pipeline steps count as "done" once the run is off the live pipeline path. */
function doneBoundaryFor(state: string, lastBusyIndex: number): number {
  switch (state) {
    case 'SUCCEEDED':
    case 'PUBLISH_FAILED':
      return PUBLISH_INDEX; // the tracked pipeline finished; publish wasn't (yet, or it failed)
    case 'PUBLISHED':
      return PIPELINE_STEPS.length; // everything, including publish, finished
    case 'AWAITING_APPROVAL':
    case 'REJECTED':
      return ANALYSIS_DONE_BOUNDARY; // analysis finished; execution never started
    default:
      // FAILED or CANCELLED can happen from any step (RunState.withAbort), so the only
      // honest source of truth is what this session actually observed live -- lastBusyIndex
      // is the step that was *current* (in progress, not yet done) when it broke off.
      return lastBusyIndex;
  }
}

function formatElapsed(millis: number): string {
  const totalSeconds = Math.max(0, Math.floor(millis / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

/** Ticks live while `until` is null; freezes at `until - since` once the run has one. */
function useElapsed(since: string, until: string | null): number {
  const end = () => (until ? new Date(until).getTime() : Date.now()) - new Date(since).getTime();
  const [elapsed, setElapsed] = useState(end);

  useEffect(() => {
    setElapsed(end());
    if (until) {
      return;
    }
    const id = setInterval(() => setElapsed(end()), 1000);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [since, until]);

  return elapsed;
}

interface AnalysisProgressProps {
  state: string;
  since: string;
  until: string;
  events?: RunEvent[];
}

/**
 * A "the platform is working" indicator that stays visible for a run's
 * whole lifecycle -- not just while genuinely busy -- pinned to the top of
 * the viewport (see .progress-card's position: sticky). Animation (pulsing
 * dot, shimmering bar, ticking timer, a highlighted "current" step) is only
 * shown while stateTone(state) === 'busy'; every other tone renders the same
 * tracker statically, with completed steps kept marked done rather than
 * reset or removed.
 *
 * Collapsed by default -- the step tracker is enough at a glance. Expanding
 * reveals the full event timeline *inside* this same card, so it stays
 * pinned alongside the indicator (sticky) rather than living further down
 * the page, detached from the thing it explains.
 */
export function AnalysisProgress({ state, since, until, events = [] }: AnalysisProgressProps) {
  const [expanded, setExpanded] = useState(false);
  const tone = stateTone(state);
  const isBusy = tone === 'busy';
  const liveIndex = PIPELINE_STEPS.findIndex((step) => step.state === state);

  // Remembers the furthest pipeline step actually observed live this session,
  // so a run that fails or is cancelled mid-flight keeps showing real
  // progress instead of losing it once state moves off that step.
  const lastBusyIndexRef = useRef(-1);
  if (isBusy && liveIndex >= 0) {
    lastBusyIndexRef.current = liveIndex;
  }

  const doneBoundary = isBusy ? liveIndex : doneBoundaryFor(state, lastBusyIndexRef.current);
  const currentIndex = isBusy ? liveIndex : -1;
  const elapsed = useElapsed(since, isTerminal(state) ? until : null);
  const label = isBusy ? `${STEP_LABELS[state] ?? 'Working'}…` : (OUTCOME_LABELS[state] ?? state);
  const fillPercent = Math.max(0, Math.min(100, (Math.max(doneBoundary, 0) / PIPELINE_STEPS.length) * 100));

  return (
    <section className={`card progress-card tone-${tone}`} aria-live="polite">
      <div className="progress-headline">
        <span className={isBusy ? 'progress-dot' : 'progress-dot static'} aria-hidden="true" />
        <span className="progress-label">{label}</span>
        <span className="progress-elapsed">{formatElapsed(elapsed)}</span>
        <button
          type="button"
          className="progress-toggle"
          aria-expanded={expanded}
          aria-controls="progress-timeline"
          onClick={() => setExpanded((current) => !current)}
        >
          {expanded ? 'Hide timeline ▾' : 'Show timeline ▸'}
        </button>
      </div>
      <div className="progress-bar" aria-hidden="true">
        <div
          className={isBusy ? 'progress-bar-fill' : 'progress-bar-fill static'}
          style={isBusy ? undefined : { width: `${fillPercent}%` }}
        />
      </div>
      <ol className="progress-steps">
        {PIPELINE_STEPS.map((step, index) => (
          <li key={step.state} className={index < doneBoundary ? 'done' : index === currentIndex ? 'current' : 'pending'}>
            {step.label}
          </li>
        ))}
      </ol>
      {expanded && (
        <div className="progress-details" id="progress-timeline">
          <Timeline events={events} />
        </div>
      )}
    </section>
  );
}
