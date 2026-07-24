import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
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
    oldSpecFile: 'local:v1.yaml',
    newSpecFile: 'local:v2.yaml',
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

function stubRun(detail: RunDetail) {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify(detail), { status: 200 })));
}

function NewRunProbe() {
  const location = useLocation();
  return <div data-testid="new-run-probe">{JSON.stringify(location.state)}</div>;
}

function renderWithNewRunRoute(initialEntry: { pathname: string }) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/runs/:runId" element={<RunDetailPage />} />
        <Route path="/new" element={<NewRunProbe />} />
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

  it('scrolls to the failure section for a failed run', async () => {
    stubRun(runDetail({
      state: 'FAILED',
      failure: {
        category: 'VALIDATION_FAILED',
        message: 'Build failed after the bounded repair attempt.',
        mutationOccurred: true,
        artifactId: null,
        remediation: 'Inspect the build log and start a fresh run once fixed.',
      },
    }));
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderRunDetail({ pathname: '/runs/run-1', state: { scrollTo: 'failure' } });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('demo')).toBeInTheDocument();

    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(document.getElementById('failure')).toHaveClass('highlight-pulse');
  });

  it('scrolls to the publish section for a succeeded remote run', async () => {
    stubRun(runDetail({ state: 'SUCCEEDED', remoteRepository: true }));
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderRunDetail({ pathname: '/runs/run-1', state: { scrollTo: 'publish' } });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('demo')).toBeInTheDocument();

    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(document.getElementById('publish')).toHaveClass('highlight-pulse');
  });
});

describe('RunDetailPage Run again', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('navigates to New Run carrying the exact same repository and qualified spec IDs', async () => {
    stubRun(runDetail({
      repositoryId: 'acme-consumer',
      oldSpecFile: 'source:acme-openapi:v1.yaml',
      newSpecFile: 'source:acme-openapi:v2.yaml',
    }));

    renderWithNewRunRoute({ pathname: '/runs/run-1' });
    await screen.findByText('demo');

    fireEvent.click(screen.getByText('Run again'));

    const carried = JSON.parse(screen.getByTestId('new-run-probe').textContent ?? 'null');
    expect(carried).toEqual({
      repositoryId: 'acme-consumer',
      oldSpec: 'source:acme-openapi:v1.yaml',
      newSpec: 'source:acme-openapi:v2.yaml',
    });
  });

  it('does not show Run again when the run has no recorded spec IDs', async () => {
    stubRun(runDetail({ oldSpecFile: null, newSpecFile: null }));

    renderWithNewRunRoute({ pathname: '/runs/run-1' });
    await screen.findByText('demo');

    expect(screen.queryByText('Run again')).not.toBeInTheDocument();
  });
});
