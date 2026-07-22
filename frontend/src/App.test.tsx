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

beforeEach(() => {
  StubEventSource.instances.length = 0;
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('App critical journey', () => {
  it('creates a run from the setup form and shows its dashboard', async () => {
    const user = userEvent.setup();
    let created = false;
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
        created = true;
        return { status: 201, body: { id: 'run-1', name: 'demo', state: 'CREATED' } };
      }
      if (url === '/api/runs') {
        return { body: created ? [{ id: 'run-1', name: 'demo', state: 'AWAITING_APPROVAL' }] : [] };
      }
      if (url === '/api/runs/run-1') {
        return { body: awaitingApprovalRun };
      }
      return null;
    });

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

    // AWAITING_APPROVAL is waiting on the human, not the system -- no busy indicator.
    expect(screen.queryByText(/Comparing specifications|Drafting migration plan/)).not.toBeInTheDocument();
  });

  it('approves the plan against its hash and then offers execution', async () => {
    const user = userEvent.setup();
    let approved = false;
    const spy = installFetch((url, init) => {
      if (url === '/api/setup') {
        return { body: { repositories: [], specifications: [], remoteRepositories: [], specSources: [] } };
      }
      if (url === '/api/runs' && !init?.method) {
        return { body: [{ id: 'run-1', name: 'demo', state: 'AWAITING_APPROVAL' }] };
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
      if (url === '/api/setup') return { body: { repositories: [], specifications: [], remoteRepositories: [], specSources: [] } };
      if (url === '/api/runs' && !init?.method) {
        return { body: [{ id: 'run-1', name: 'demo', state: 'DIFFING' }] };
      }
      if (url === '/api/runs/run-1') {
        detailFetches += 1;
        return { body: { ...awaitingApprovalRun, state: 'DIFFING', plan: null } };
      }
      return null;
    });

    render(<App />);
    await user.click(await screen.findByText('demo'));
    await waitFor(() => expect(StubEventSource.instances.length).toBe(1));

    // A busy state shows the live "working" indicator with its step label.
    expect(await screen.findByText('Comparing specifications…')).toBeInTheDocument();

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
      if (url === '/api/setup') return { body: { repositories: [], specifications: [], remoteRepositories: [], specSources: [] } };
      if (url === '/api/runs' && !init?.method) {
        return { body: [{ id: 'run-1', name: 'demo', state: 'FAILED' }] };
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

    render(<App />);
    await user.click(await screen.findByText('demo'));

    await screen.findByText(/Run failed: DIRTY_REPOSITORY/);
    expect(screen.getByText(/repository has uncommitted changes/)).toBeInTheDocument();
    expect(screen.getByText('no')).toBeInTheDocument();
    expect(screen.getByText(/Commit, stash or reset/)).toBeInTheDocument();
  });
});
