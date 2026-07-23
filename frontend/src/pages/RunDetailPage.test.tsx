import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { RunDetailPage } from './RunDetailPage';
import type { RunDetail } from '../types';

function runDetail(overrides: Partial<RunDetail> = {}): RunDetail {
  return {
    id: 'run-1',
    name: 'demo',
    state: 'AWAITING_APPROVAL',
    repositoryId: 'customer-consumer',
    traceId: 'trace-12345678',
    createdAt: '2026-07-23T10:00:00Z',
    updatedAt: '2026-07-23T10:01:00Z',
    oldSpecName: 'v1.yaml',
    newSpecName: 'v2.yaml',
    oldSpecHash: 'aaa',
    newSpecHash: 'bbb',
    originalBranch: null,
    workingBranch: null,
    failure: null,
    pullRequestUrl: null,
    remoteRepository: false,
    approval: null,
    changes: [],
    evidence: [],
    assessments: [],
    plan: null,
    patches: [],
    validations: [],
    ...overrides,
  };
}

function renderRunDetail(initialEntry: { pathname: string; state?: unknown }) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/runs/:runId" element={<RunDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RunDetailPage scroll-to-section', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async () => new Response(JSON.stringify(runDetail()), { status: 200 })),
    );
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('scrolls to and highlights the plan-approval section when navigated with scrollTo state', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderRunDetail({ pathname: '/runs/run-1', state: { scrollTo: 'plan-approval' } });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('demo')).toBeInTheDocument();

    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    const section = document.getElementById('plan-approval');
    expect(section).toHaveClass('highlight-pulse');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1700);
    });
    expect(section).not.toHaveClass('highlight-pulse');
  });

  it('does not scroll when opened normally, without navigation state', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderRunDetail({ pathname: '/runs/run-1' });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('demo')).toBeInTheDocument();

    expect(scrollIntoView).not.toHaveBeenCalled();
  });
});
