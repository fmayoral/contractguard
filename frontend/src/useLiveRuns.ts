import { useEffect, useState } from 'react';
import { api } from './api';
import type { RunSummary } from './types';

export const LIVE_RUNS_POLL_MS = 3000;

/**
 * Keeps the run list fresh via polling. Simpler and safer than one SSE
 * subscription per row: no backend change needed (RunDetailPage's
 * per-run useRunEvents stays push-based), and a few seconds of staleness
 * is imperceptible for a local, single-operator tool.
 */
export function useLiveRuns(): RunSummary[] {
  const [runs, setRuns] = useState<RunSummary[]>([]);

  useEffect(() => {
    let cancelled = false;
    const load = () => {
      api.listRuns().then((next) => {
        if (!cancelled) {
          setRuns(next);
        }
      }).catch(() => {
        // Keep showing the last known list rather than clearing it on a transient error.
      });
    };
    load();
    const id = setInterval(load, LIVE_RUNS_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  return runs;
}
