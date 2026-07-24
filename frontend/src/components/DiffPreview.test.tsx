import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DiffPreview } from './DiffPreview';
import type { Change, DiffPreview as DiffPreviewData } from '../types';

function change(overrides: Partial<Change> & { id: string; classification: string }): Change {
  return {
    type: 'ENDPOINT_RENAMED',
    method: 'GET',
    path: '/customers/{id}',
    schema: null,
    property: null,
    oldValue: '/customers/{id}',
    newValue: '/v2/customers/{id}',
    reason: 'ENDPOINT_PATH_CHANGED',
    explanation: null,
    ...overrides,
  };
}

function mockPreviewFetch(preview: DiffPreviewData) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(preview), { status: 200 })));
}

describe('DiffPreview', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows a loading hint, then the change count, classification breakdown and warnings', async () => {
    mockPreviewFetch({
      changes: [
        change({ id: 'c1', classification: 'BREAKING' }),
        change({ id: 'c2', classification: 'NON_BREAKING' }),
      ],
      warnings: ['request bodies with more than one content type are not compared'],
    });

    render(<DiffPreview oldSpec="local:v1.yaml" newSpec="local:v2.yaml" />);
    expect(screen.getByText('Comparing specifications…')).toBeInTheDocument();

    expect(await screen.findByText('Preview: 2 changes detected')).toBeInTheDocument();
    expect(screen.getByText('Breaking')).toBeInTheDocument();
    expect(screen.getByText('request bodies with more than one content type are not compared')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('expands to the full change table on demand', async () => {
    mockPreviewFetch({ changes: [change({ id: 'c1', classification: 'BREAKING' })], warnings: [] });
    const user = userEvent.setup();
    render(<DiffPreview oldSpec="local:v1.yaml" newSpec="local:v2.yaml" />);

    await screen.findByText('Preview: 1 change detected');
    await user.click(screen.getByText('Show details'));

    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getByText('ENDPOINT_RENAMED')).toBeInTheDocument();

    await user.click(screen.getByText('Hide details'));
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('reports zero changes plainly, without a classification breakdown', async () => {
    mockPreviewFetch({ changes: [], warnings: [] });
    render(<DiffPreview oldSpec="local:v1.yaml" newSpec="local:v1.yaml" />);

    expect(await screen.findByText('Preview: 0 changes detected')).toBeInTheDocument();
    expect(screen.queryByText('Show details')).not.toBeInTheDocument();
  });

  it('shows an error message when the preview fails to load', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('boom', { status: 500 })));
    render(<DiffPreview oldSpec="local:v1.yaml" newSpec="local:v2.yaml" />);

    expect(await screen.findByText(/HTTP 500/)).toBeInTheDocument();
  });

  it('refetches when the selected specs change', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ changes: [], warnings: [] }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchSpy);
    const { rerender } = render(<DiffPreview oldSpec="local:v1.yaml" newSpec="local:v2.yaml" />);

    await screen.findByText('Preview: 0 changes detected');
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    rerender(<DiffPreview oldSpec="local:v1.yaml" newSpec="local:v3.yaml" />);

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(2));
    expect(fetchSpy.mock.calls[1][0]).toContain('newSpec=local%3Av3.yaml');
  });
});
