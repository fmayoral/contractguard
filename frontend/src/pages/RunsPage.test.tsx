import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { RunsPage } from './RunsPage';
import { LIVE_RUNS_POLL_MS } from '../useLiveRuns';
import type { RunSummary } from '../types';

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

// vi.advanceTimersByTimeAsync(0) flushes the pending fetch promise chain while fake timers are
// active -- plain `waitFor` polls with real timers and never resolves once they are, so it can't
// be used here (see useLiveRuns.test.ts for the same constraint).
async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

describe('RunsPage', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('reflects a run state change without a remount -- the reported bug where PLANNING never became AWAITING_APPROVAL', async () => {
    let state = 'PLANNING';
    const fetchSpy = vi.fn().mockImplementation(async () =>
      new Response(JSON.stringify([runRow(state)]), { status: 200 }));
    vi.stubGlobal('fetch', fetchSpy);

    render(<MemoryRouter><RunsPage /></MemoryRouter>);
    await flush();

    expect(screen.getByText('PLANNING')).toBeInTheDocument();

    state = 'AWAITING_APPROVAL';
    await act(async () => {
      await vi.advanceTimersByTimeAsync(LIVE_RUNS_POLL_MS);
    });

    expect(screen.queryByText('PLANNING')).not.toBeInTheDocument();
    expect(screen.getByText('AWAITING_APPROVAL')).toBeInTheDocument();
  });
});
