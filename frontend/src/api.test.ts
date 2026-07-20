import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './api';

function mockFetch(status: number, body: unknown, contentType = 'application/json') {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  const response = new Response(text, { status, headers: { 'Content-Type': contentType } });
  const spy = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', spy);
  return spy;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('api client', () => {
  it('creates runs with a JSON body', async () => {
    const spy = mockFetch(201, { id: 'run-1', state: 'CREATED' });

    const run = await api.createRun('demo', 'customer-consumer', 'v1.yaml', 'v2.yaml');

    expect(run.id).toBe('run-1');
    const [url, init] = spy.mock.calls[0];
    expect(url).toBe('/api/runs');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({
      name: 'demo',
      repositoryId: 'customer-consumer',
      oldSpec: 'v1.yaml',
      newSpec: 'v2.yaml',
    });
  });

  it('sends approval decisions against the plan hash', async () => {
    const spy = mockFetch(200, { id: 'run-1' });

    await api.decide('run-1', 'APPROVED', 'hash-1');

    const [url, init] = spy.mock.calls[0];
    expect(url).toBe('/api/runs/run-1/approval');
    expect(JSON.parse(init.body)).toEqual({ decision: 'APPROVED', planHash: 'hash-1' });
  });

  it('triggers publish with no body', async () => {
    const spy = mockFetch(202, { id: 'run-1', state: 'PUBLISHING' });

    await api.publish('run-1');

    const [url, init] = spy.mock.calls[0];
    expect(url).toBe('/api/runs/run-1/publish');
    expect(init.method).toBe('POST');
  });

  it('registers a remote repository with a JSON body', async () => {
    const spy = mockFetch(201, {
      repositoryId: 'acme-widgets',
      owner: 'acme',
      name: 'widgets',
      defaultBranch: 'main',
      registeredAt: '2026-07-20T10:00:00Z',
    });

    const registered = await api.registerRemoteRepository(
      'acme-widgets',
      'https://github.com/acme/widgets',
      'main',
      'gh-token',
    );

    expect(registered.owner).toBe('acme');
    const [url, init] = spy.mock.calls[0];
    expect(url).toBe('/api/repositories/remote');
    expect(JSON.parse(init.body)).toEqual({
      repositoryId: 'acme-widgets',
      cloneUrl: 'https://github.com/acme/widgets',
      defaultBranch: 'main',
      token: 'gh-token',
    });
  });

  it('surfaces problem details as typed errors', async () => {
    mockFetch(409, {
      detail: 'approval hash stale',
      category: 'APPROVAL_MISMATCH',
      remediation: 'Reload the plan.',
    });

    const error = await api.execute('run-1').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(409);
    expect(apiError.message).toBe('approval hash stale');
    expect(apiError.category).toBe('APPROVAL_MISMATCH');
    expect(apiError.remediation).toBe('Reload the plan.');
  });

  it('handles non-JSON error bodies', async () => {
    mockFetch(502, 'Bad gateway', 'text/plain');

    const error = await api.listRuns().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('HTTP 502');
  });

  it('fetches markdown reports as text', async () => {
    mockFetch(200, '# Report', 'text/markdown');
    await expect(api.markdownReport('run-1')).resolves.toBe('# Report');

    mockFetch(404, 'missing', 'text/plain');
    await expect(api.markdownReport('run-1')).rejects.toBeInstanceOf(ApiError);
  });

  it('builds report download urls', () => {
    expect(api.reportJsonUrl('r1')).toBe('/api/runs/r1/artifacts/report.json');
    expect(api.reportMarkdownUrl('r1')).toBe('/api/runs/r1/artifacts/report.md');
  });
});
