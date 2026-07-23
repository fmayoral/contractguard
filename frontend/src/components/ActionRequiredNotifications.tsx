import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useLiveRuns } from '../useLiveRuns';

const EXIT_ANIMATION_MS = 280;

interface Toast {
  runId: string;
  name: string;
  repositoryId: string;
  leaving: boolean;
}

function runPath(runId: string): string {
  return `/runs/${runId}`;
}

/**
 * AWAITING_APPROVAL is the one point in a run's life where a human must act
 * (approve or reject the plan) before anything else happens -- every other
 * state either progresses on its own or is terminal. This watches every run
 * from wherever the user currently is in the app and raises a toast the
 * moment one crosses into it, so a transition that lands minutes after the
 * user has moved on to something else doesn't go unnoticed until their next
 * visit to the Runs page.
 *
 * Mounted once, outside <Routes>, so it survives navigation and keeps polling
 * regardless of which page is open.
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
    // The very first batch only seeds the baseline -- a run already awaiting approval
    // before the app was opened shouldn't pop a notification the moment it loads.
    const isBaseline = !seeded.current;
    seeded.current = true;

    if (!isBaseline) {
      for (const run of runs) {
        const before = previous.get(run.id);
        const justEnteredApproval = run.state === 'AWAITING_APPROVAL' && before !== 'AWAITING_APPROVAL';
        const alreadyViewingIt = location.pathname === runPath(run.id);
        if (justEnteredApproval && !alreadyViewingIt) {
          setToasts((current) =>
            current.some((t) => t.runId === run.id)
              ? current
              : [...current, { runId: run.id, name: run.name, repositoryId: run.repositoryId, leaving: false }],
          );
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

  const open = (runId: string) => {
    dismiss(runId);
    navigate(runPath(runId), { state: { scrollTo: 'plan-approval' } });
  };

  if (toasts.length === 0) return null;

  return (
    <div className="toast-stack" role="region" aria-label="Notifications">
      {toasts.map((toast) => (
        <div
          key={toast.runId}
          className={`toast${toast.leaving ? ' toast-leaving' : ''}`}
          role="alert"
          tabIndex={0}
          onClick={() => open(toast.runId)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') open(toast.runId);
          }}
        >
          <span className="toast-icon" aria-hidden="true">
            ⚠
          </span>
          <div className="toast-body">
            <strong>{toast.name}</strong>
            <p className="muted">{toast.repositoryId} is awaiting your approval</p>
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
