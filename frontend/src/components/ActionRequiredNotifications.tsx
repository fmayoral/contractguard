import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useLiveRuns } from '../useLiveRuns';
import type { RunSummary } from '../types';

const EXIT_ANIMATION_MS = 280;

type Tone = 'warn' | 'ok' | 'bad';

interface NotifiableState {
  tone: Tone;
  icon: string;
  message: (run: RunSummary) => string;
  /** Element id on RunDetailPage to land on; undefined means "just open the run". */
  scrollTo: (run: RunSummary) => string | undefined;
}

/**
 * The states where a run needs a human to look at it: approve/reject a plan, publish (or retry
 * publishing) a succeeded run, or understand why one failed. Every other state either progresses
 * on its own (still in flight) or is an outcome the user already knows about because they caused
 * it themselves (REJECTED, CANCELLED, PUBLISHED all result directly from a button the user just
 * clicked on that exact run's page).
 */
const NOTIFIABLE_STATES: Record<string, NotifiableState> = {
  AWAITING_APPROVAL: {
    tone: 'warn',
    icon: '⚠',
    message: (run) => `${run.repositoryId} is awaiting your approval`,
    scrollTo: () => 'plan-approval',
  },
  SUCCEEDED: {
    tone: 'ok',
    icon: '✓',
    message: (run) => (run.remoteRepository ? `${run.repositoryId} succeeded — ready to publish` : `${run.repositoryId} succeeded`),
    scrollTo: (run) => (run.remoteRepository ? 'publish' : undefined),
  },
  FAILED: {
    tone: 'bad',
    icon: '✕',
    message: (run) => `${run.repositoryId} failed`,
    scrollTo: () => 'failure',
  },
  PUBLISH_FAILED: {
    tone: 'bad',
    icon: '✕',
    message: (run) => `${run.repositoryId} publish failed`,
    scrollTo: () => 'publish',
  },
};

interface Toast {
  runId: string;
  name: string;
  tone: Tone;
  icon: string;
  message: string;
  scrollTo: string | undefined;
  leaving: boolean;
}

function runPath(runId: string): string {
  return `/runs/${runId}`;
}

/**
 * Watches every run from wherever the user currently is in the app and raises a toast the moment
 * one crosses into a state listed in {@link NOTIFIABLE_STATES}, so a transition that lands
 * minutes after the user has moved on to something else doesn't go unnoticed until their next
 * visit to the Runs page.
 *
 * Mounted once, outside <Routes>, so it survives navigation and keeps polling regardless of which
 * page is open.
 */
export function ActionRequiredNotifications() {
  const { runs, loaded } = useLiveRuns();
  const location = useLocation();
  const navigate = useNavigate();
  const [toasts, setToasts] = useState<Toast[]>([]);
  const knownStates = useRef(new Map<string, string>());
  const seeded = useRef(false);

  useEffect(() => {
    if (!loaded) return;
    const previous = knownStates.current;
    // The very first batch only seeds the baseline -- a run already awaiting approval (or
    // already done) before the app was opened shouldn't pop a notification the moment it loads.
    const isBaseline = !seeded.current;
    seeded.current = true;

    if (!isBaseline) {
      for (const run of runs) {
        const before = previous.get(run.id);
        const config = NOTIFIABLE_STATES[run.state];
        const justEntered = config && before !== run.state;
        const alreadyViewingIt = location.pathname === runPath(run.id);
        if (justEntered && !alreadyViewingIt) {
          const toast: Toast = {
            runId: run.id,
            name: run.name,
            tone: config.tone,
            icon: config.icon,
            message: config.message(run),
            scrollTo: config.scrollTo(run),
            leaving: false,
          };
          // Replace rather than stack a second toast for the same run -- e.g. a
          // still-open AWAITING_APPROVAL toast for a run that has since succeeded.
          setToasts((current) => [...current.filter((t) => t.runId !== run.id), toast]);
        }
      }
    }

    const next = new Map<string, string>();
    for (const run of runs) next.set(run.id, run.state);
    knownStates.current = next;
    // Only re-run when the polled list actually changes -- location is read for its
    // current value, not to retrigger transition detection on every navigation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runs, loaded]);

  // Opening the run yourself is an implicit acknowledgement of it.
  useEffect(() => {
    setToasts((current) => current.filter((t) => location.pathname !== runPath(t.runId)));
  }, [location.pathname]);

  const dismiss = (runId: string) => {
    setToasts((current) => current.map((t) => (t.runId === runId ? { ...t, leaving: true } : t)));
    setTimeout(() => {
      setToasts((current) => current.filter((t) => t.runId !== runId));
    }, EXIT_ANIMATION_MS);
  };

  const open = (toast: Toast) => {
    dismiss(toast.runId);
    navigate(runPath(toast.runId), toast.scrollTo ? { state: { scrollTo: toast.scrollTo } } : undefined);
  };

  if (toasts.length === 0) return null;

  return (
    <div className="toast-stack" role="region" aria-label="Notifications">
      {toasts.map((toast) => (
        <div
          key={toast.runId}
          className={`toast toast-${toast.tone}${toast.leaving ? ' toast-leaving' : ''}`}
          role="alert"
          tabIndex={0}
          onClick={() => open(toast)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') open(toast);
          }}
        >
          <span className="toast-icon" aria-hidden="true">
            {toast.icon}
          </span>
          <div className="toast-body">
            <strong>{toast.name}</strong>
            <p className="muted">{toast.message}</p>
          </div>
          <button
            className="toast-dismiss"
            aria-label={`Dismiss notification for ${toast.name}`}
            onClick={(e) => {
              e.stopPropagation();
              dismiss(toast.runId);
            }}
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
