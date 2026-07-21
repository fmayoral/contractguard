// Wire types mirroring the backend DTOs (RunDtos).

export interface RunSummary {
  id: string;
  name: string;
  state: string;
  repositoryId: string;
  createdAt: string;
  updatedAt: string;
  workingBranch: string | null;
  failureCategory: string | null;
  pullRequestUrl: string | null;
  remoteRepository: boolean;
}

export interface Failure {
  category: string;
  message: string;
  mutationOccurred: boolean;
  artifactId: string | null;
  remediation: string;
}

export interface ApprovalInfo {
  decision: string;
  planHash: string;
  decidedAt: string;
}

export interface Change {
  id: string;
  type: string;
  classification: string;
  method: string | null;
  path: string | null;
  schema: string | null;
  property: string | null;
  oldValue: string | null;
  newValue: string | null;
  reason: string;
  explanation: string | null;
}

export interface Evidence {
  id: string;
  apiChangeId: string;
  relativePath: string;
  startLine: number;
  endLine: number;
  snippet: string;
  searchTerm: string;
  relationship: string;
}

export interface Assessment {
  id: string;
  apiChangeId: string;
  component: string;
  severity: string;
  confidence: string;
  failureMode: string;
  recommendedAction: string;
  assumptions: string[];
  evidenceIds: string[];
}

export interface PlanItem {
  id: string;
  objective: string;
  expectedFiles: string[];
  proposedAction: string;
  testsToUpdate: string[];
  validationCommand: string;
  risk: string;
  rollback: string;
  evidenceIds: string[];
}

export interface Plan {
  id: string;
  version: number;
  hash: string;
  createdAt: string;
  items: PlanItem[];
  approvedFiles: string[];
}

export interface Patch {
  id: string;
  attempt: number;
  checkStatus: string;
  changedPaths: string[];
  appliedAt: string | null;
  unifiedDiff: string;
}

export interface Validation {
  attempt: number;
  command: string;
  exitCode: number;
  startedAt: string;
  durationMillis: number;
  summary: string;
  successful: boolean;
  outputArtifactId: string | null;
}

export interface RunDetail {
  id: string;
  name: string;
  state: string;
  repositoryId: string;
  traceId: string;
  createdAt: string;
  updatedAt: string;
  oldSpecName: string | null;
  newSpecName: string | null;
  oldSpecHash: string | null;
  newSpecHash: string | null;
  originalBranch: string | null;
  workingBranch: string | null;
  failure: Failure | null;
  pullRequestUrl: string | null;
  remoteRepository: boolean;
  approval: ApprovalInfo | null;
  changes: Change[];
  evidence: Evidence[];
  assessments: Assessment[];
  plan: Plan | null;
  patches: Patch[];
  validations: Validation[];
}

export interface RunEvent {
  runId: string;
  seq: number;
  occurredAt: string;
  step: string;
  status: string;
  message: string;
  metadata: string | null;
}

// Mirrors com.contractguard.application.service.SpecOption.SpecOrigin's name().toLowerCase().
export interface SpecOption {
  id: string;
  label: string;
  origin: 'local' | 'uploaded' | 'spec_source';
  sourceId: string | null;
}

export interface SetupOptions {
  repositories: string[];
  specifications: SpecOption[];
  remoteRepositories: RemoteRepositorySummary[];
  specSources: RemoteRepositorySummary[];
}

export interface RemoteRepositorySummary {
  repositoryId: string;
  owner: string;
  name: string;
  defaultBranch: string;
  registeredAt: string;
}
