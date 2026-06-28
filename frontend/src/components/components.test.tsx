import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Timeline } from './Timeline';
import { ValidationView } from './ValidationView';
import { ReportView } from './ReportView';
import { RunList } from './RunList';
import type { Patch, RunEvent, Validation } from '../types';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Timeline', () => {
  it('renders events with their kind flag', () => {
    const events: RunEvent[] = [
      {
        runId: 'r',
        seq: 1,
        occurredAt: '2026-07-18T10:00:00Z',
        step: 'assessment',
        status: 'TOOL',
        message: 'search_repository: "fullName"',
        metadata: '{"kind":"tool"}',
      },
      {
        runId: 'r',
        seq: 2,
        occurredAt: '2026-07-18T10:00:05Z',
        step: 'planning',
        status: 'COMPLETED',
        message: 'Plan v1 with 3 item(s) ready',
        metadata: '{"kind":"llm"}',
      },
    ];
    render(<Timeline events={events} />);
    expect(screen.getByText('search_repository: "fullName"')).toBeInTheDocument();
    expect(screen.getByText('tool')).toBeInTheDocument();
    expect(screen.getByText('llm')).toBeInTheDocument();
  });

  it('shows a waiting hint without events', () => {
    render(<Timeline events={[]} />);
    expect(screen.getByText(/Waiting for events/)).toBeInTheDocument();
  });
});

describe('ValidationView', () => {
  const patch: Patch = {
    id: 'p-1',
    attempt: 1,
    checkStatus: 'APPLIED',
    changedPaths: ['src/A.java'],
    appliedAt: '2026-07-18T10:02:00Z',
    unifiedDiff: '--- a/src/A.java\n+++ b/src/A.java\n',
  };
  const validation: Validation = {
    attempt: 1,
    command: 'maven-verify',
    exitCode: 0,
    startedAt: '2026-07-18T10:02:10Z',
    durationMillis: 32000,
    summary: 'BUILD SUCCESS — Tests run: 4, Failures: 0',
    successful: true,
    outputArtifactId: 'validation-attempt-1.log',
  };

  it('shows branch, patch and validation outcome', () => {
    render(
      <ValidationView
        originalBranch="main"
        workingBranch="contractguard/run-abc"
        patches={[patch]}
        validations={[validation]}
      />,
    );
    expect(screen.getByText('contractguard/run-abc')).toBeInTheDocument();
    expect(screen.getByText('APPLIED')).toBeInTheDocument();
    expect(screen.getByText('PASSED')).toBeInTheDocument();
    expect(screen.getByText(/32.0 s/)).toBeInTheDocument();
    expect(screen.queryByText('repair attempt used')).not.toBeInTheDocument();
  });

  it('flags a used repair attempt', () => {
    render(
      <ValidationView
        originalBranch="main"
        workingBranch="contractguard/run-abc"
        patches={[patch, { ...patch, id: 'p-2', attempt: 2 }]}
        validations={[validation, { ...validation, attempt: 2, successful: false, exitCode: 1 }]}
      />,
    );
    expect(screen.getByText('repair attempt used')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('hints when remediation has not started', () => {
    render(<ValidationView originalBranch={null} workingBranch={null} patches={[]} validations={[]} />);
    expect(screen.getByText(/Remediation has not started/)).toBeInTheDocument();
  });
});

describe('ReportView', () => {
  it('is gated until the run is terminal', () => {
    render(<ReportView runId="run-1" terminal={false} />);
    expect(screen.getByText(/becomes available when the run finishes/)).toBeInTheDocument();
  });

  it('loads and renders the markdown report with download links', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('# ContractGuard Report\n\n**done**', { status: 200 })),
    );
    const user = userEvent.setup();
    render(<ReportView runId="run-1" terminal={true} />);

    expect(screen.getByText('Download Markdown')).toHaveAttribute(
      'href',
      '/api/runs/run-1/artifacts/report.md',
    );
    expect(screen.getByText('Download JSON')).toHaveAttribute(
      'href',
      '/api/runs/run-1/artifacts/report.json',
    );

    await user.click(screen.getByText('Load report'));
    expect(await screen.findByText('ContractGuard Report')).toBeInTheDocument();
  });
});

describe('RunList', () => {
  it('marks the selected run', async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(
      <RunList
        runs={[
          {
            id: 'a',
            name: 'first',
            state: 'SUCCEEDED',
            repositoryId: 'r',
            createdAt: '',
            updatedAt: '',
            workingBranch: null,
            failureCategory: null,
          },
        ]}
        selectedId="a"
        onSelect={onSelect}
      />,
    );
    expect(screen.getByRole('button')).toHaveClass('selected');
    await user.click(screen.getByText('first'));
    expect(onSelect).toHaveBeenCalledWith('a');
  });

  it('hints when empty', () => {
    render(<RunList runs={[]} selectedId={null} onSelect={() => undefined} />);
    expect(screen.getByText('No runs yet.')).toBeInTheDocument();
  });
});
