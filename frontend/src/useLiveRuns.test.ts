import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useLiveRuns, LIVE_RUNS_POLL_MS } from './useLiveRuns';
import type { RunSummary } from './types';

function runRow(state: string): RunSummary {
  return {
    id: 'run-1',
    name: 'demo',
    state,
    repositoryId: 'customer-consumer',
    createdAt: '2026-07-23T10:00:00Z',
    updatedAt: '2026-07-23T10:00:00Z',
    workingBranch: null,
    failureCategory: null,
    pullRequestUrl: null,
    remoteRepository: false,
  };
}

// vi.advanceTimersByTimeAsync(0) both flushes pending microtasks (the fetch promise chain)
// and advances fake timers -- plain `waitFor` polls with real timers and never resolves
// once fake timers are active, so it can't be used here.
async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

describe('useLiveRuns', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('starts unloaded, then fetches immediately on mount and again every poll interval', async () => {
    let state = 'PLANNING';
    const fetchSpy = vi.fn().mockImplementation(async () =>
      new Response(JSON.stringify([runRow(state)]), { status: 200 }));
    vi.stubGlobal('fetch', fetchSpy);

    const { result } = renderHook(() => useLiveRuns());
    expect(result.current.loaded).toBe(false);

    await flush();

    expect(result.current.loaded).toBe(true);
    expect(result.current.runs).toHaveLength(1);
    expect(result.current.runs[0].state).toBe('PLANNING');
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    // The state changes server-side between polls -- exactly the bug this hook fixes
    // (the Runs page never noticed PLANNING becoming AWAITING_APPROVAL without a reload).
    state = 'AWAITING_APPROVAL';
    await act(async () => {
      await vi.advanceTimersByTimeAsync(LIVE_RUNS_POLL_MS);
    });

    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(result.current.runs[0].state).toBe('AWAITING_APPROVAL');
  });

  it('keeps the last known list instead of clearing it on a transient fetch error', async () => {
    let shouldFail = false;
    const fetchSpy = vi.fn().mockImplementation(async () => {
      if (shouldFail) {
        throw new Error('network down');
      }
      return new Response(JSON.stringify([runRow('SUCCEEDED')]), { status: 200 });
    });
    vi.stubGlobal('fetch', fetchSpy);

    const { result } = renderHook(() => useLiveRuns());
    await flush();
    expect(result.current.runs).toHaveLength(1);

    shouldFail = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(LIVE_RUNS_POLL_MS);
    });

    expect(result.current.loaded).toBe(true);
    expect(result.current.runs).toHaveLength(1);
    expect(result.current.runs[0].state).toBe('SUCCEEDED');
  });

  it('stops polling once the component unmounts', async () => {
    const fetchSpy = vi.fn().mockImplementation(async () =>
      new Response(JSON.stringify([runRow('SUCCEEDED')]), { status: 200 }));
    vi.stubGlobal('fetch', fetchSpy);

    const { unmount } = renderHook(() => useLiveRuns());
    await flush();
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(LIVE_RUNS_POLL_MS * 3);
    });

    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });
});
