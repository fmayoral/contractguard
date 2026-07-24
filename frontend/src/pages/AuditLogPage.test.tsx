import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuditLogPage } from './AuditLogPage';
import type { AuditEntry } from '../types';

function entry(overrides: Partial<AuditEntry> & { id: string }): AuditEntry {
  return {
    runId: 'run-1',
    repositoryId: 'customer-consumer',
    principal: 'local-operator',
    eventType: 'STATE_TRANSITION',
    detail: 'CREATED -> VALIDATING_INPUT',
    planHash: null,
    occurredAt: '2026-07-24T10:00:00Z',
    ...overrides,
  };
}

function mockFetch(entries: AuditEntry[]) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(entries), { status: 200 })));
}

function renderPage() {
  return render(
    <MemoryRouter>
      <AuditLogPage />
    </MemoryRouter>,
  );
}

describe('AuditLogPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('lists every entry newest-first, linking each row to its run', async () => {
    mockFetch([
      entry({ id: 'a-1', occurredAt: '2026-07-24T10:00:00Z', detail: 'CREATED -> VALIDATING_INPUT' }),
      entry({
        id: 'a-2',
        occurredAt: '2026-07-24T10:01:00Z',
        eventType: 'APPROVAL_DECISION',
        detail: 'Plan approved',
        planHash: 'hash-abc123456789',
      }),
    ]);
    renderPage();

    expect(await screen.findByText('Plan approved')).toBeInTheDocument();
    const rows = screen.getAllByRole('row').slice(1); // drop the header row
    expect(rows[0]).toHaveTextContent('Plan approved');
    expect(rows[1]).toHaveTextContent('CREATED -> VALIDATING_INPUT');
    expect(screen.getByText('hash-abc1234')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /run-1/ })[0]).toHaveAttribute('href', '/runs/run-1');
  });

  it('shows a hint when nothing has been recorded yet', async () => {
    mockFetch([]);
    renderPage();

    expect(await screen.findByText('No audit entries recorded yet.')).toBeInTheDocument();
  });

  it('filters by free text across repository, run, principal and detail', async () => {
    mockFetch([
      entry({ id: 'a-1', repositoryId: 'customer-consumer', detail: 'CREATED -> VALIDATING_INPUT' }),
      entry({ id: 'a-2', repositoryId: 'billing-service', detail: 'CREATED -> VALIDATING_INPUT' }),
    ]);
    renderPage();
    const table = await screen.findByRole('table');
    await within(table).findByText('customer-consumer');

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Filter audit entries'), 'billing');

    expect(within(table).queryByText('customer-consumer')).not.toBeInTheDocument();
    expect(within(table).getByText('billing-service')).toBeInTheDocument();
  });

  it('filters by repository and by event type via the dropdowns', async () => {
    mockFetch([
      entry({ id: 'a-1', repositoryId: 'customer-consumer', eventType: 'STATE_TRANSITION' }),
      entry({
        id: 'a-2',
        repositoryId: 'customer-consumer',
        eventType: 'APPROVAL_DECISION',
        detail: 'Plan approved',
      }),
      entry({ id: 'a-3', repositoryId: 'billing-service', eventType: 'STATE_TRANSITION' }),
    ]);
    renderPage();
    await screen.findByText('Plan approved');

    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText('Filter by repository'), 'billing-service');
    expect(screen.getAllByRole('row')).toHaveLength(2); // header + the one billing-service row
    await user.selectOptions(screen.getByLabelText('Filter by repository'), 'all');

    await user.selectOptions(screen.getByLabelText('Filter by event type'), 'APPROVAL_DECISION');
    expect(screen.getAllByRole('row')).toHaveLength(2); // header + the one approval-decision row
    expect(screen.getByText('Plan approved')).toBeInTheDocument();
  });

  it('refetches when Refresh is clicked', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response(JSON.stringify([entry({ id: 'a-1' })]), { status: 200 }));
    vi.stubGlobal('fetch', fetchSpy);
    renderPage();
    await screen.findByText('CREATED -> VALIDATING_INPUT');
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(2));
  });

  it('shows an error message if the audit log fails to load', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    renderPage();

    expect(await screen.findByText('network down')).toBeInTheDocument();
  });
});
