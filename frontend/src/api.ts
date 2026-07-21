import type {
  RemoteRepositorySummary,
  RunDetail,
  RunEvent,
  RunSummary,
  SetupOptions,
  SpecOption,
} from './types';

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

function problemDetailError(status: number, text: string): ApiError {
  let detail = `HTTP ${status}`;
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
  return new ApiError(status, detail, category, remediation);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  const text = await response.text();
  if (!response.ok) {
    throw problemDetailError(response.status, text);
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
  publish(runId: string): Promise<RunSummary> {
    return request(`/api/runs/${runId}/publish`, { method: 'POST' });
  },
  registerRemoteRepository(
    repositoryId: string,
    cloneUrl: string,
    defaultBranch: string,
    token: string,
  ): Promise<RemoteRepositorySummary> {
    return request('/api/repositories/remote', {
      method: 'POST',
      body: JSON.stringify({ repositoryId, cloneUrl, defaultBranch, token }),
    });
  },
  deregisterRemoteRepository(repositoryId: string): Promise<void> {
    return request(`/api/repositories/remote/${encodeURIComponent(repositoryId)}`, { method: 'DELETE' });
  },
  registerSpecSource(
    repositoryId: string,
    cloneUrl: string,
    defaultBranch: string,
    token: string,
  ): Promise<RemoteRepositorySummary> {
    return request('/api/spec-sources', {
      method: 'POST',
      // A blank token registers an unauthenticated (public-repository) spec source (ADR-0012).
      body: JSON.stringify({ repositoryId, cloneUrl, defaultBranch, token: token || undefined }),
    });
  },
  deregisterSpecSource(repositoryId: string): Promise<void> {
    return request(`/api/spec-sources/${encodeURIComponent(repositoryId)}`, { method: 'DELETE' });
  },
  deleteUploadedSpecification(fileName: string): Promise<void> {
    return request(`/api/specs/${encodeURIComponent(fileName)}`, { method: 'DELETE' });
  },
  async uploadSpecification(file: File): Promise<SpecOption> {
    const formData = new FormData();
    formData.append('file', file);
    const response = await fetch('/api/specs', { method: 'POST', body: formData });
    const text = await response.text();
    if (!response.ok) {
      throw problemDetailError(response.status, text);
    }
    return JSON.parse(text) as SpecOption;
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
