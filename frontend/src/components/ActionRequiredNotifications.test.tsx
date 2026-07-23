import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ActionRequiredNotifications } from './ActionRequiredNotifications';
import { LIVE_RUNS_POLL_MS } from '../useLiveRuns';
import type { RunSummary } from '../types';

function runRow(id: string, state: string): RunSummary {
  return {
    id,
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

function LocationProbe() {
  const location = useLocation();
  return (
    <div>
      <div data-testid="location">
        {location.pathname}::{JSON.stringify(location.state)}
      </div>
      <Link to="/runs/run-1">go to run-1</Link>
    </div>
  );
}

function renderAt(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <ActionRequiredNotifications />
      <Routes>
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

// vi.advanceTimersByTimeAsync(0) flushes the pending fetch promise chain while fake timers are
// active -- plain `waitFor` polls with real timers and never resolves once they are.
async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

async function poll() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(LIVE_RUNS_POLL_MS);
  });
}

describe('ActionRequiredNotifications', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('does not notify for a run that is already awaiting approval when the app first loads', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () =>
        new Response(JSON.stringify([runRow('run-1', 'AWAITING_APPROVAL')]), { status: 200 })),
    );

    renderAt('/');
    await flush();
    await poll();

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('raises a toast the moment a run transitions into awaiting approval', async () => {
    let state = 'PLANNING';
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () => new Response(JSON.stringify([runRow('run-1', state)]), { status: 200 })),
    );

    renderAt('/');
    await flush();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    state = 'AWAITING_APPROVAL';
    await poll();

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('demo')).toBeInTheDocument();
    expect(screen.getByText('customer-consumer is awaiting your approval')).toBeInTheDocument();
  });

  it('does not notify while the user is already viewing that exact run', async () => {
    let state = 'PLANNING';
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () => new Response(JSON.stringify([runRow('run-1', state)]), { status: 200 })),
    );

    renderAt('/runs/run-1');
    await flush();

    state = 'AWAITING_APPROVAL';
    await poll();

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('clears an existing toast once the user navigates to that run themselves', async () => {
    let state = 'PLANNING';
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () => new Response(JSON.stringify([runRow('run-1', state)]), { status: 200 })),
    );

    renderAt('/');
    await flush();
    state = 'AWAITING_APPROVAL';
    await poll();
    expect(screen.getByRole('alert')).toBeInTheDocument();

    fireEvent.click(screen.getByText('go to run-1'));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('clicking the toast navigates to the run, asks for a scroll to the plan-approval section, and dismisses', async () => {
    let state = 'PLANNING';
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () => new Response(JSON.stringify([runRow('run-1', state)]), { status: 200 })),
    );

    renderAt('/');
    await flush();
    state = 'AWAITING_APPROVAL';
    await poll();

    fireEvent.click(screen.getByRole('alert'));

    expect(screen.getByTestId('location').textContent).toContain('/runs/run-1');
    expect(screen.getByTestId('location').textContent).toContain('plan-approval');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('the dismiss button removes the toast without navigating', async () => {
    let state = 'PLANNING';
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () => new Response(JSON.stringify([runRow('run-1', state)]), { status: 200 })),
    );

    renderAt('/');
    await flush();
    state = 'AWAITING_APPROVAL';
    await poll();

    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toMatch(/^\//);
    expect(screen.getByTestId('location').textContent).not.toContain('/runs/run-1');
  });
});
