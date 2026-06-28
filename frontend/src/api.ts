import type { RunDetail, RunEvent, RunSummary, SetupOptions } from './types';

export class ApiError extends Error {
  readonly status: number;
  readonly category: string | null;
  readonly remediation: string | null;

  constructor(status: number, detail: string, category: string | null, remediation: string | null) {
    super(detail);
    this.status = status;
    this.category = category;
    this.remediation = remediation;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  const text = await response.text();
  if (!response.ok) {
    let detail = `HTTP ${response.status}`;
    let category: string | null = null;
    let remediation: string | null = null;
    try {
      const problem = JSON.parse(text);
      detail = problem.detail ?? detail;
      category = problem.category ?? null;
      remediation = problem.remediation ?? null;
    } catch {
      // non-JSON error body; keep the generic detail
    }
    throw new ApiError(response.status, detail, category, remediation);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  setup(): Promise<SetupOptions> {
    return request('/api/setup');
  },
  listRuns(): Promise<RunSummary[]> {
    return request('/api/runs');
  },
  getRun(runId: string): Promise<RunDetail> {
    return request(`/api/runs/${runId}`);
  },
  createRun(name: string, repositoryId: string, oldSpec: string, newSpec: string): Promise<RunSummary> {
    return request('/api/runs', {
      method: 'POST',
      body: JSON.stringify({ name, repositoryId, oldSpec, newSpec }),
    });
  },
  decide(runId: string, decision: 'APPROVED' | 'REJECTED', planHash: string): Promise<RunDetail> {
    return request(`/api/runs/${runId}/approval`, {
      method: 'POST',
      body: JSON.stringify({ decision, planHash }),
    });
  },
  execute(runId: string): Promise<RunSummary> {
    return request(`/api/runs/${runId}/execute`, { method: 'POST' });
  },
  events(runId: string): Promise<RunEvent[]> {
    return request(`/api/runs/${runId}/events/list`);
  },
  async markdownReport(runId: string): Promise<string> {
    const response = await fetch(`/api/runs/${runId}/artifacts/report.md`);
    if (!response.ok) {
      throw new ApiError(response.status, 'report unavailable', null, null);
    }
    return response.text();
  },
  reportJsonUrl(runId: string): string {
    return `/api/runs/${runId}/artifacts/report.json`;
  },
  reportMarkdownUrl(runId: string): string {
    return `/api/runs/${runId}/artifacts/report.md`;
  },
};
