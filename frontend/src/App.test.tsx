import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import type { RunDetail } from './types';
import { StubEventSource } from './test-setup';

const awaitingApprovalRun: RunDetail = {
  id: 'run-1',
  name: 'demo',
  state: 'AWAITING_APPROVAL',
  repositoryId: 'customer-consumer',
  traceId: 'trace-12345678',
  createdAt: '2026-07-18T10:00:00Z',
  updatedAt: '2026-07-18T10:01:00Z',
  oldSpecName: 'customer-api-v1.yaml',
  newSpecName: 'customer-api-v2.yaml',
  oldSpecFile: 'local:customer-api-v1.yaml',
  newSpecFile: 'local:customer-api-v2.yaml',
  oldSpecHash: 'aaa',
  newSpecHash: 'bbb',
  originalBranch: null,
  workingBranch: null,
  failure: null,
  pullRequestUrl: null,
  remoteRepository: false,
  approval: null,
  changes: [
    {
      id: 'ch-1',
      type: 'PROPERTY_RENAMED',
      classification: 'BREAKING',
      method: null,
      path: null,
      schema: 'Customer',
      property: 'fullName',
      oldValue: 'fullName',
      newValue: 'displayName',
      reason: 'PROPERTY_RENAMED_CONSUMERS_READ_NULL',
      explanation: 'Consumers reading fullName will now receive null.',
    },
  ],
  evidence: [
    {
      id: 'ev-1',
      apiChangeId: 'ch-1',
      relativePath: 'src/main/java/Customer.java',
      startLine: 12,
      endLine: 12,
      snippet: 'private String fullName;',
      searchTerm: 'fullName',
      relationship: 'READS_RENAMED_PROPERTY',
    },
  ],
  assessments: [
    {
      id: 'as-1',
      apiChangeId: 'ch-1',
      component: 'Customer.java',
      severity: 'HIGH',
      confidence: 'HIGH',
      failureMode: 'Deserialised field is null',
      recommendedAction: 'Rename to displayName',
      assumptions: [],
      evidenceIds: ['ev-1'],
    },
  ],
  plan: {
    id: 'plan-1',
    version: 1,
    hash: 'hash-abc123456789',
    createdAt: '2026-07-18T10:01:00Z',
    items: [
      {
        id: 'item-1',
        objective: 'Rename consumer field fullName to displayName',
        expectedFiles: ['src/main/java/Customer.java'],
        proposedAction: 'Rename the field and accessors',
        testsToUpdate: ['src/test/java/CustomerTest.java'],
        validationCommand: 'maven-verify',
        risk: 'low: mechanical rename',
        rollback: 'Discard the working branch',
        evidenceIds: ['ev-1'],
      },
    ],
    approvedFiles: ['src/main/java/Customer.java', 'src/test/java/CustomerTest.java'],
  },
  patches: [],
  validations: [],
};

const emptyStatistics = {
  totalRuns: 0,
  activeRuns: 0,
  runsByState: {},
  runsPerDay: [{ day: '2026-07-23', count: 0 }],
  totalChanges: 0,
  changesByType: {},
  changesByClassification: {},
  remediation: {
    validatedRuns: 0,
    firstPassRuns: 0,
    repairAttempts: 0,
    repairedRuns: 0,
    averageValidationMillis: 0,
    linesAdded: 0,
    linesRemoved: 0,
    filesTouched: 0,
  },
};

const runListRow = {
  id: 'run-1',
  name: 'demo',
  state: 'AWAITING_APPROVAL',
  repositoryId: 'customer-consumer',
  createdAt: '2026-07-18T10:00:00Z',
  updatedAt: '2026-07-18T10:01:00Z',
  workingBranch: null,
  failureCategory: null,
  pullRequestUrl: null,
  remoteRepository: false,
};

type FetchHandler = (url: string, init?: RequestInit) => { status?: number; body: unknown } | null;

function installFetch(handler: FetchHandler) {
  const spy = vi.fn(async (url: string, init?: RequestInit) => {
    const result = handler(url, init);
    if (!result) {
      throw new Error(`unexpected fetch: ${url}`);
    }
    const body = typeof result.body === 'string' ? result.body : JSON.stringify(result.body);
    return new Response(body, {
      status: result.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', spy);
  return spy;
}

/** BrowserRouter reads the real jsdom URL, which persists across tests — pin it per test. */
function startAt(path: string) {
  window.history.replaceState({}, '', path);
}

beforeEach(() => {
  StubEventSource.instances.length = 0;
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('App critical journey', () => {
  it('creates a run from the New run page and lands on its detail', async () => {
    const user = userEvent.setup();
    installFetch((url, init) => {
      if (url === '/api/setup') {
        return {
          body: {
            repositories: ['customer-consumer'],
            specifications: [
              { id: 'local:customer-api-v1.yaml', label: 'customer-api-v1.yaml', origin: 'local', sourceId: null },
              { id: 'local:customer-api-v2.yaml', label: 'customer-api-v2.yaml', origin: 'local', sourceId: null },
            ],
            remoteRepositories: [],
            specSources: [],
          },
        };
      }
      if (url === '/api/runs' && init?.method === 'POST') {
        return { status: 201, body: { id: 'run-1', name: 'demo', state: 'CREATED' } };
      }
      if (url === '/api/runs/run-1') {
        return { body: awaitingApprovalRun };
      }
      if (url.startsWith('/api/spec-diff')) {
        return { body: { changes: [], warnings: [] } };
      }
      return null;
    });

    startAt('/new');
    render(<App />);

    await screen.findByText('Start analysis');
    await user.click(screen.getByText('Start analysis'));

    // Change table with classification, values and explanation.
    await screen.findByText('PROPERTY_RENAMED');
    expect(screen.getByText('BREAKING')).toBeInTheDocument();
    expect(screen.getAllByText('displayName').length).toBeGreaterThan(0);
    expect(screen.getByText(/Consumers reading fullName/)).toBeInTheDocument();

    // Impact evidence with file and line.
    expect(screen.getAllByText('src/main/java/Customer.java').length).toBeGreaterThan(0);
    expect(screen.getByText('private String fullName;')).toBeInTheDocument();

    // Plan with risk, rollback and the mutation warning.
    expect(screen.getByText(/low: mechanical rename/)).toBeInTheDocument();
    expect(screen.getByText(/Approving authorises ContractGuard/)).toBeInTheDocument();
    expect(screen.getByText('Approve plan')).toBeEnabled();

    // AWAITING_APPROVAL is waiting on the human, not the system -- the progress indicator stays
    // visible but shows a waiting state, not an in-progress step label.
    expect(screen.getByText('Waiting for your approval')).toBeInTheDocument();
    expect(screen.queryByText(/Comparing specifications|Drafting migration plan/)).not.toBeInTheDocument();
  });

  it('approves the plan against its hash and then offers execution', async () => {
    const user = userEvent.setup();
    let approved = false;
    const spy = installFetch((url, init) => {
      if (url === '/api/runs' && !init?.method) {
        return { body: [runListRow] };
      }
      if (url === '/api/runs/run-1/approval' && init?.method === 'POST') {
        approved = true;
        return { body: awaitingApprovalRun };
      }
      if (url === '/api/runs/run-1/execute' && init?.method === 'POST') {
        return { status: 202, body: { id: 'run-1', state: 'PREPARING_BRANCH' } };
      }
      if (url === '/api/runs/run-1') {
        return {
          body: approved
            ? {
                ...awaitingApprovalRun,
                approval: {
                  decision: 'APPROVED',
                  planHash: awaitingApprovalRun.plan!.hash,
                  decidedAt: '2026-07-18T10:05:00Z',
                },
              }
            : awaitingApprovalRun,
        };
      }
      return null;
    });

    startAt('/runs');
    render(<App />);
    await user.click(await screen.findByText('demo'));
    await user.click(await screen.findByText('Approve plan'));

    const approvalCall = spy.mock.calls.find(([url]) => url === '/api/runs/run-1/approval');
    expect(approvalCall).toBeDefined();
    expect(JSON.parse(approvalCall![1]!.body as string)).toEqual({
      decision: 'APPROVED',
      planHash: 'hash-abc123456789',
    });

    await user.click(await screen.findByText('Execute remediation'));
    await waitFor(() =>
      expect(spy.mock.calls.some(([url]) => url === '/api/runs/run-1/execute')).toBe(true),
    );
  });

  it('streams SSE events into the timeline and refreshes the run', async () => {
    const user = userEvent.setup();
    let detailFetches = 0;
    installFetch((url, init) => {
      if (url === '/api/runs' && !init?.method) {
        return { body: [{ ...runListRow, state: 'DIFFING' }] };
      }
      if (url === '/api/runs/run-1') {
        detailFetches += 1;
        return { body: { ...awaitingApprovalRun, state: 'DIFFING', plan: null } };
      }
      return null;
    });

    startAt('/runs');
    render(<App />);
    await user.click(await screen.findByText('demo'));
    await waitFor(() => expect(StubEventSource.instances.length).toBe(1));

    // A busy state shows the live "working" indicator with its step label.
    expect(await screen.findByText('Comparing specifications…')).toBeInTheDocument();

    // The timeline lives inside the progress indicator, collapsed by default.
    await user.click(screen.getByText('Show timeline ▸'));

    const fetchesBeforeEvent = detailFetches;

    StubEventSource.instances[0].emit('run-event', {
      runId: 'run-1',
      seq: 1,
      occurredAt: '2026-07-18T10:00:30Z',
      step: 'diff',
      status: 'COMPLETED',
      message: '4 change(s) detected',
      metadata: '{"kind":"tool"}',
    });

    await screen.findByText('4 change(s) detected');
    expect(screen.getByText('diff/COMPLETED')).toBeInTheDocument();
    await waitFor(() => expect(detailFetches).toBeGreaterThan(fetchesBeforeEvent));
  });

  it('shows the failure card with mutation state for failed runs', async () => {
    const user = userEvent.setup();
    installFetch((url, init) => {
      if (url === '/api/runs' && !init?.method) {
        return { body: [{ ...runListRow, state: 'FAILED' }] };
      }
      if (url === '/api/runs/run-1') {
        return {
          body: {
            ...awaitingApprovalRun,
            state: 'FAILED',
            failure: {
              category: 'DIRTY_REPOSITORY',
              message: 'repository has uncommitted changes',
              mutationOccurred: false,
              artifactId: null,
              remediation: 'Commit, stash or reset the repository, then execute again.',
            },
          },
        };
      }
      return null;
    });

    startAt('/runs');
    render(<App />);
    await user.click(await screen.findByText('demo'));

    await screen.findByText(/Run failed: DIRTY_REPOSITORY/);
    expect(screen.getByText(/repository has uncommitted changes/)).toBeInTheDocument();
    expect(screen.getByText('no')).toBeInTheDocument();
    expect(screen.getByText(/Commit, stash or reset/)).toBeInTheDocument();
  });
});

describe('Dashboard', () => {
  it('renders stat tiles, charts and recent runs from the statistics endpoint', async () => {
    installFetch((url, init) => {
      if (url === '/api/statistics') {
        return {
          body: {
            ...emptyStatistics,
            totalRuns: 9,
            activeRuns: 1,
            runsByState: { SUCCEEDED: 2, FAILED: 1, DIFFING: 1 },
            runsPerDay: [
              { day: '2026-07-22', count: 1 },
              { day: '2026-07-23', count: 3 },
            ],
            totalChanges: 6,
            changesByType: { PROPERTY_RENAMED: 4, ENDPOINT_ADDED: 2 },
            changesByClassification: { BREAKING: 4, NON_BREAKING: 2 },
            remediation: {
              validatedRuns: 2,
              firstPassRuns: 1,
              repairAttempts: 1,
              repairedRuns: 1,
              averageValidationMillis: 30000,
              linesAdded: 12,
              linesRemoved: 4,
              filesTouched: 3,
            },
          },
        };
      }
      if (url === '/api/runs' && !init?.method) {
        return { body: [runListRow] };
      }
      return null;
    });

    startAt('/');
    render(<App />);

    // Tiles: totals, success rate (2 of 3 terminal), breaking changes, lines remediated.
    expect(await screen.findByText('Total runs')).toBeInTheDocument();
    expect(screen.getByText('9')).toBeInTheDocument();
    expect(screen.getByText('67%')).toBeInTheDocument();
    expect(screen.getByText('+12 −4')).toBeInTheDocument();

    // Outcome donut legend carries every label and count (colour is never the only channel).
    expect(screen.getByText('Succeeded')).toBeInTheDocument();
    expect(screen.getByText('Failed')).toBeInTheDocument();

    // Change-type bars are labelled with their counts.
    expect(screen.getByText('property renamed')).toBeInTheDocument();

    // The run is non-terminal (AWAITING_APPROVAL), so it shows in both the Active-now
    // banner and Recent runs -- both link through to the run detail.
    expect(screen.getByText('1 run in progress')).toBeInTheDocument();
    const demoLinks = screen.getAllByRole('link', { name: /demo/ });
    expect(demoLinks).toHaveLength(2);
    demoLinks.forEach((link) => expect(link).toHaveAttribute('href', '/runs/run-1'));
  });

  it('offers a first-run call to action when no runs exist yet', async () => {
    installFetch((url, init) => {
      if (url === '/api/statistics') return { body: emptyStatistics };
      if (url === '/api/runs' && !init?.method) return { body: [] };
      return null;
    });

    startAt('/');
    render(<App />);

    expect(await screen.findByText('No runs yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Start your first run' })).toHaveAttribute('href', '/new');
  });
});
