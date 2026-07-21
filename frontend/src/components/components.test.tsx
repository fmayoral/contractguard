import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Timeline } from './Timeline';
import { ValidationView } from './ValidationView';
import { ReportView } from './ReportView';
import { RunList } from './RunList';
import { DiffView } from './DiffView';
import { ThemeToggle } from './ThemeToggle';
import { PublishPanel } from './PublishPanel';
import { RegisterRemoteRepository } from './RegisterRemoteRepository';
import { RegisterSpecSource } from './RegisterSpecSource';
import { RunSetup } from './RunSetup';
import { UploadSpecification } from './UploadSpecification';
import { WizardStepRepository } from './WizardStepRepository';
import { WizardStepSpecs } from './WizardStepSpecs';
import type { Patch, RunEvent, SetupOptions, Validation } from '../types';

function mockRoutedFetch(handler: (url: string, init?: RequestInit) => { status?: number; body: unknown } | null) {
  const spy = vi.fn(async (url: string, init?: RequestInit) => {
    const result = handler(url, init);
    if (!result) throw new Error(`unexpected fetch: ${url}`);
    const body = typeof result.body === 'string' ? result.body : JSON.stringify(result.body);
    return new Response(body, { status: result.status ?? 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', spy);
  return spy;
}

function mockJsonFetch(status: number, body: unknown) {
  const spy = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  );
  vi.stubGlobal('fetch', spy);
  return spy;
}

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

describe('DiffView', () => {
  const javaDiff = [
    '--- a/src/main/java/com/example/A.java',
    '+++ b/src/main/java/com/example/A.java',
    '@@ -3,2 +3,2 @@',
    ' public class A {',
    '-    private String fullName = "x";',
    '+    private String displayName = "x";',
    '',
  ].join('\r\n');

  it('renders one section per file with add/remove counts and line numbers', () => {
    const { container } = render(<DiffView diff={javaDiff} />);
    expect(screen.getByText('src/main/java/com/example/A.java')).toBeInTheDocument();
    expect(container.querySelector('.diff-stat-add')?.textContent).toBe('+1');
    expect(container.querySelector('.diff-stat-del')?.textContent).toBe('−1');
    const added = container.querySelector('.diff-row.diff-add');
    expect(added?.textContent).toContain('displayName');
    expect(added?.querySelectorAll('.diff-gutter')[1]?.textContent).toBe('4');
  });

  it('syntax-highlights code for known languages', () => {
    const { container } = render(<DiffView diff={javaDiff} />);
    const keywords = [...container.querySelectorAll('.token.keyword')].map((el) => el.textContent);
    expect(keywords).toContain('private');
    expect(container.querySelector('.token.string')?.textContent).toBe('"x"');
  });

  it('renders unknown file types without tokens and hints on an empty diff', () => {
    const { container } = render(
      <DiffView diff={'--- a/notes.unknownext\n+++ b/notes.unknownext\n@@ -1 +1 @@\n-old\n+new\n'} />,
    );
    expect(container.querySelector('.token')).toBeNull();
    expect(container.querySelector('.diff-row.diff-add')?.textContent).toContain('new');

    render(<DiffView diff="" />);
    expect(screen.getByText('Empty diff.')).toBeInTheDocument();
  });
});

describe('ThemeToggle', () => {
  afterEach(() => {
    localStorage.clear();
    delete document.documentElement.dataset.theme;
  });

  it('applies the default dark theme and toggles to light, persisting the choice', async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);
    expect(document.documentElement.dataset.theme).toBe('dark');

    await user.click(screen.getByRole('button', { name: /switch to light theme/i }));
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(localStorage.getItem('contractguard-theme')).toBe('light');

    await user.click(screen.getByRole('button', { name: /switch to dark theme/i }));
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('contractguard-theme')).toBe('dark');
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
            pullRequestUrl: null,
            remoteRepository: false,
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

describe('PublishPanel', () => {
  it('hints that publishing waits for remediation to succeed', () => {
    render(
      <PublishPanel
        runId="run-1"
        state="PLANNING"
        remoteRepository={false}
        pullRequestUrl={null}
        onChanged={() => undefined}
      />,
    );
    expect(screen.getByText(/becomes available once remediation succeeds/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('explains that a local repository has nothing to publish', () => {
    render(
      <PublishPanel
        runId="run-1"
        state="SUCCEEDED"
        remoteRepository={false}
        pullRequestUrl={null}
        onChanged={() => undefined}
      />,
    );
    expect(screen.getByText(/was not registered for remote publishing/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('publishes a succeeded run and refreshes on completion', async () => {
    const user = userEvent.setup();
    const spy = mockJsonFetch(202, { id: 'run-1', state: 'PUBLISHING' });
    const onChanged = vi.fn();
    render(
      <PublishPanel
        runId="run-1"
        state="SUCCEEDED"
        remoteRepository={true}
        pullRequestUrl={null}
        onChanged={onChanged}
      />,
    );

    await user.click(screen.getByText('Publish (push & open draft PR)'));

    expect(spy).toHaveBeenCalledWith('/api/runs/run-1/publish', expect.objectContaining({ method: 'POST' }));
    expect(onChanged).toHaveBeenCalled();
  });

  it('shows a pushing hint while publishing', () => {
    render(
      <PublishPanel
        runId="run-1"
        state="PUBLISHING"
        remoteRepository={true}
        pullRequestUrl={null}
        onChanged={() => undefined}
      />,
    );
    expect(screen.getByText(/Pushing the branch and opening the pull request/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('offers a retry button and shows the failure hint when publishing failed', () => {
    render(
      <PublishPanel
        runId="run-1"
        state="PUBLISH_FAILED"
        remoteRepository={true}
        pullRequestUrl={null}
        onChanged={() => undefined}
      />,
    );
    expect(screen.getByText('Retry publish')).toBeInTheDocument();
    expect(screen.getByText(/Publish failed/)).toBeInTheDocument();
  });

  it('links to the draft pull request once published', () => {
    render(
      <PublishPanel
        runId="run-1"
        state="PUBLISHED"
        remoteRepository={true}
        pullRequestUrl="https://github.com/acme/widgets/pull/7"
        onChanged={() => undefined}
      />,
    );
    expect(screen.getByText('https://github.com/acme/widgets/pull/7')).toHaveAttribute(
      'href',
      'https://github.com/acme/widgets/pull/7',
    );
  });

  it('surfaces a typed error if publishing fails', async () => {
    const user = userEvent.setup();
    mockJsonFetch(409, { detail: 'run is not SUCCEEDED', remediation: 'Wait for remediation to finish.' });
    render(
      <PublishPanel
        runId="run-1"
        state="SUCCEEDED"
        remoteRepository={true}
        pullRequestUrl={null}
        onChanged={() => undefined}
      />,
    );

    await user.click(screen.getByText('Publish (push & open draft PR)'));

    expect(await screen.findByText(/run is not SUCCEEDED/)).toBeInTheDocument();
  });
});

describe('RegisterRemoteRepository', () => {
  it('registers a repository, clears the form and notifies the parent', async () => {
    const user = userEvent.setup();
    const spy = mockJsonFetch(201, {
      repositoryId: 'acme-widgets',
      owner: 'acme',
      name: 'widgets',
      defaultBranch: 'main',
      registeredAt: '2026-07-20T10:00:00Z',
    });
    const onRegistered = vi.fn();
    render(<RegisterRemoteRepository onRegistered={onRegistered} />);

    await user.click(screen.getByText('+ Register a GitHub repository'));
    await user.type(screen.getByPlaceholderText('acme-widgets'), 'acme-widgets');
    await user.type(screen.getByPlaceholderText('https://github.com/acme/widgets'), 'https://github.com/acme/widgets');
    await user.type(screen.getByLabelText('Personal access token (repo scope)'), 'gh-token');
    await user.click(screen.getByText('Register repository'));

    expect(spy).toHaveBeenCalledWith(
      '/api/repositories/remote',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(JSON.parse(spy.mock.calls[0][1].body)).toEqual({
      repositoryId: 'acme-widgets',
      cloneUrl: 'https://github.com/acme/widgets',
      defaultBranch: 'main',
      token: 'gh-token',
    });
    expect(onRegistered).toHaveBeenCalledWith('acme-widgets');
    expect(screen.getByPlaceholderText('acme-widgets')).toHaveValue('');
  });

  it('surfaces a typed error and keeps the form open on failure', async () => {
    const user = userEvent.setup();
    mockJsonFetch(400, {
      detail: "clone URL is not a supported GitHub HTTPS URL",
      remediation: 'Use an https://github.com/{owner}/{repo} clone URL.',
    });
    const onRegistered = vi.fn();
    render(<RegisterRemoteRepository onRegistered={onRegistered} />);

    await user.click(screen.getByText('+ Register a GitHub repository'));
    await user.type(screen.getByPlaceholderText('acme-widgets'), 'acme-widgets');
    await user.type(screen.getByPlaceholderText('https://github.com/acme/widgets'), 'not-a-url');
    await user.type(screen.getByLabelText('Personal access token (repo scope)'), 'gh-token');
    await user.click(screen.getByText('Register repository'));

    expect(await screen.findByText(/not a supported GitHub HTTPS URL/)).toBeInTheDocument();
    expect(onRegistered).not.toHaveBeenCalled();
  });
});

describe('RegisterSpecSource', () => {
  it('registers a public spec source with a blank token', async () => {
    const user = userEvent.setup();
    const spy = mockJsonFetch(201, {
      repositoryId: 'openapi-specs',
      owner: 'acme',
      name: 'openapi-specs',
      defaultBranch: 'main',
      registeredAt: '2026-07-21T10:00:00Z',
    });
    const onRegistered = vi.fn();
    render(<RegisterSpecSource onRegistered={onRegistered} />);

    await user.click(screen.getByText('+ Register a spec repository'));
    await user.type(screen.getByPlaceholderText('openapi-specs'), 'openapi-specs');
    await user.type(
      screen.getByPlaceholderText('https://github.com/acme/openapi-specs'),
      'https://github.com/acme/openapi-specs',
    );
    await user.click(screen.getByText('Register spec repository'));

    expect(JSON.parse(spy.mock.calls[0][1].body)).toEqual({
      repositoryId: 'openapi-specs',
      cloneUrl: 'https://github.com/acme/openapi-specs',
      defaultBranch: 'main',
      token: undefined,
    });
    expect(onRegistered).toHaveBeenCalledWith('openapi-specs');
  });

  it('surfaces a typed error on failure', async () => {
    const user = userEvent.setup();
    mockJsonFetch(400, {
      detail: 'clone URL is not a supported GitHub HTTPS URL',
      remediation: 'Use an https://github.com URL.',
    });
    const onRegistered = vi.fn();
    render(<RegisterSpecSource onRegistered={onRegistered} />);

    await user.click(screen.getByText('+ Register a spec repository'));
    await user.type(screen.getByPlaceholderText('openapi-specs'), 'openapi-specs');
    await user.type(screen.getByPlaceholderText('https://github.com/acme/openapi-specs'), 'not-a-url');
    await user.click(screen.getByText('Register spec repository'));

    expect(await screen.findByText(/not a supported GitHub HTTPS URL/)).toBeInTheDocument();
    expect(onRegistered).not.toHaveBeenCalled();
  });
});

describe('UploadSpecification', () => {
  it('uploads a selected file and notifies the parent with its qualified id', async () => {
    const user = userEvent.setup();
    const spy = mockJsonFetch(201, { id: 'upload:mine.yaml', label: 'mine.yaml', origin: 'uploaded', sourceId: null });
    const onUploaded = vi.fn();
    render(<UploadSpecification onUploaded={onUploaded} />);

    const file = new File(['openapi: 3.0.3'], 'mine.yaml', { type: 'application/yaml' });
    const input = screen.getByLabelText('Upload a specification file');
    await user.upload(input, file);

    expect(spy).toHaveBeenCalledWith('/api/specs', expect.objectContaining({ method: 'POST' }));
    await waitFor(() => expect(onUploaded).toHaveBeenCalledWith('upload:mine.yaml'));
  });

  it('surfaces a typed error when the upload is rejected', async () => {
    const user = userEvent.setup();
    mockJsonFetch(413, {
      detail: 'uploaded file exceeds the configured size limit',
      remediation: 'Upload a smaller specification file.',
    });
    const onUploaded = vi.fn();
    render(<UploadSpecification onUploaded={onUploaded} />);

    // Must match the input's accept filter (.yaml/.yml/.json) or user-event silently drops the
    // selection before it ever reaches the component -- the rejection here is a server-side one.
    const file = new File(['not really valid'], 'huge.yaml', { type: 'application/yaml' });
    await user.upload(screen.getByLabelText('Upload a specification file'), file);

    expect(await screen.findByText(/exceeds the configured size limit/)).toBeInTheDocument();
    expect(onUploaded).not.toHaveBeenCalled();
  });
});

describe('WizardStepRepository', () => {
  const options: SetupOptions = {
    repositories: ['customer-consumer'],
    remoteRepositories: ['acme-widgets'],
    specifications: [],
    specSources: [],
  };

  it('groups local and remote repositories and reports the current selection', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const { container } = render(
      <WizardStepRepository
        options={options}
        repository="customer-consumer"
        onChange={onChange}
        onRepositoryRegistered={vi.fn()}
      />,
    );

    // optgroup labels are an HTML attribute, not text content, so they're queried via the DOM directly.
    const groupLabels = [...container.querySelectorAll('optgroup')].map((g) => g.label);
    expect(groupLabels).toEqual(['Local workspace', 'Registered GitHub repositories']);
    await user.selectOptions(screen.getByLabelText('Consumer repository'), 'acme-widgets');
    expect(onChange).toHaveBeenCalledWith('acme-widgets');
  });

  it('hints when no repositories are available at all', () => {
    render(
      <WizardStepRepository
        options={{ repositories: [], remoteRepositories: [], specifications: [], specSources: [] }}
        repository=""
        onChange={vi.fn()}
        onRepositoryRegistered={vi.fn()}
      />,
    );

    expect(screen.getByText(/No repositories found/)).toBeInTheDocument();
  });
});

describe('WizardStepSpecs', () => {
  const options: SetupOptions = {
    repositories: [],
    remoteRepositories: [],
    specifications: [
      { id: 'local:old.yaml', label: 'old.yaml', origin: 'local', sourceId: null },
      { id: 'upload:mine.yaml', label: 'mine.yaml', origin: 'uploaded', sourceId: null },
      { id: 'source:openapi-specs:new.yaml', label: 'new.yaml', origin: 'spec_source', sourceId: 'openapi-specs' },
    ],
    specSources: [
      {
        repositoryId: 'openapi-specs', owner: 'acme', name: 'openapi-specs',
        defaultBranch: 'main', registeredAt: '2026-07-21T10:00:00Z',
      },
      {
        repositoryId: 'silent-specs', owner: 'acme', name: 'silent-specs',
        defaultBranch: 'main', registeredAt: '2026-07-21T10:00:00Z',
      },
    ],
  };

  it('groups specifications by origin into optgroups', () => {
    const { container } = render(
      <WizardStepSpecs
        options={options}
        oldSpec="local:old.yaml"
        newSpec="source:openapi-specs:new.yaml"
        onOldSpecChange={vi.fn()}
        onNewSpecChange={vi.fn()}
        onOptionsChanged={vi.fn()}
      />,
    );

    // optgroup labels are an HTML attribute, not text content; both selects share the same groups.
    const oldSpecSelect = screen.getByLabelText('Old specification');
    const groupLabels = [...oldSpecSelect.querySelectorAll('optgroup')].map((g) => g.label);
    expect(groupLabels).toEqual(['Local workspace', 'Uploaded', 'From openapi-specs']);
    expect(container.querySelectorAll('optgroup')).toHaveLength(6);
  });

  it('reports a registered spec source that contributed no files', () => {
    render(
      <WizardStepSpecs
        options={options}
        oldSpec="local:old.yaml"
        newSpec="source:openapi-specs:new.yaml"
        onOldSpecChange={vi.fn()}
        onNewSpecChange={vi.fn()}
        onOptionsChanged={vi.fn()}
      />,
    );

    expect(screen.getByText(/silent-specs/)).toBeInTheDocument();
    expect(screen.getByText(/contributed no files/)).toBeInTheDocument();
  });

  it('propagates old/new spec selection changes', async () => {
    const user = userEvent.setup();
    const onOldSpecChange = vi.fn();
    render(
      <WizardStepSpecs
        options={options}
        oldSpec="local:old.yaml"
        newSpec="source:openapi-specs:new.yaml"
        onOldSpecChange={onOldSpecChange}
        onNewSpecChange={vi.fn()}
        onOptionsChanged={vi.fn()}
      />,
    );

    await user.selectOptions(screen.getByLabelText('Old specification'), 'upload:mine.yaml');
    expect(onOldSpecChange).toHaveBeenCalledWith('upload:mine.yaml');
  });
});

describe('RunSetup', () => {
  it('supports going back a step without losing the earlier selection', async () => {
    const user = userEvent.setup();
    mockRoutedFetch((url) => {
      if (url === '/api/setup') {
        return {
          body: {
            repositories: ['customer-consumer'],
            remoteRepositories: [],
            specifications: [
              { id: 'local:old.yaml', label: 'old.yaml', origin: 'local', sourceId: null },
              { id: 'local:new.yaml', label: 'new.yaml', origin: 'local', sourceId: null },
            ],
            specSources: [],
          },
        };
      }
      return null;
    });
    render(<RunSetup onCreated={vi.fn()} />);

    await user.click(await screen.findByText('Next'));
    expect(await screen.findByLabelText('Old specification')).toBeInTheDocument();

    await user.click(screen.getByText('Back'));
    expect(await screen.findByLabelText('Consumer repository')).toHaveValue('customer-consumer');
  });

  it('a spec uploaded during setup fills the first empty slot without overwriting a prior choice', async () => {
    const user = userEvent.setup();
    let uploaded = false;
    mockRoutedFetch((url, init) => {
      if (url === '/api/setup') {
        return {
          body: {
            repositories: ['customer-consumer'],
            remoteRepositories: [],
            specifications: uploaded
              ? [{ id: 'upload:mine.yaml', label: 'mine.yaml', origin: 'uploaded', sourceId: null }]
              : [],
            specSources: [],
          },
        };
      }
      if (url === '/api/specs' && init?.method === 'POST') {
        uploaded = true;
        return { status: 201, body: { id: 'upload:mine.yaml', label: 'mine.yaml', origin: 'uploaded', sourceId: null } };
      }
      return null;
    });
    render(<RunSetup onCreated={vi.fn()} />);

    await user.click(await screen.findByText('Next'));
    const file = new File(['openapi: 3.0.3'], 'mine.yaml', { type: 'application/yaml' });
    await user.upload(await screen.findByLabelText('Upload a specification file'), file);

    await waitFor(() => expect(screen.getByLabelText('Old specification')).toHaveValue('upload:mine.yaml'));
  });
});
