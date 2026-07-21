# ADR-0012: Spec Source Repositories and Manual Upload

- Status: accepted
- Date: 2026-07-21

## Context

FR-027 solved "I don't have local filesystem access, but I need ContractGuard
to work with my consumer repository" by letting a user register a GitHub
repository with a token. It never solved the equivalent problem for
specifications: `contractguard.specs.directory` is a single local folder the
server process reads with `Files.list` — offering your own OpenAPI spec files
still requires filesystem or deployment access (rebuilding a container image,
bind-mounting a directory). A user testing against their own two repositories
— one consumer, one holding the OpenAPI specs — has no self-service way to
register the second one at all. This closes that gap, giving specs the same
registration story consumer repositories already have, plus a manual-upload
path for anyone with spec files but no repository for them.

## Decisions

### 1. Reuse the `RemoteRepository` domain type; keep a separate registry and table

A spec-source registration needs exactly the same shape as a consumer
registration — a GitHub HTTPS clone URL, owner/name parsed from it, a
default branch, an optional credential — so `domain.RemoteRepository` and
its `forGitHub` factory are reused as-is rather than duplicated. What
differs is the *collection* they live in: a new `SpecSourceRegistry` port
and `spec_source_repositories` table (Flyway `V4`, additive, both vendor
folders), separate from `RemoteRepositoryRegistry`/`remote_repositories`.
Keeping them separate — rather than adding a `kind` discriminator column to
the existing table — means every existing query, every place that already
checks "is this repository ID remote-registered," stays untouched; a spec
source can never accidentally be mistaken for an analysable/mutable
consumer repository, because it is never in that table at all.

### 2. The token is optional; an unauthenticated clone is a real, common case

Consumer registration requires a token (`@NotBlank`) because pushing a
branch and opening a pull request always needs write access. A spec-source
repository is read-only and very often public — this project's own demo
specs are a public repo. Requiring a token anyway (as FR-027 does) would be
friction for the common case. `RegisterSpecSourceRequest.token` is nullable;
`RemoteGitCliAdapter` now only embeds the `x-access-token` username and sets
up a `GIT_ASKPASS` helper when a non-blank credential is actually supplied,
falling back to a plain, anonymous `git clone`/`fetch` otherwise — the same
thing running `git clone <url>` by hand against a public repository does.
Embedding empty credentials unconditionally was tried mentally and rejected:
GitHub's HTTPS endpoint can reject a request that supplies blank credentials
even for a public repository, which would make the *unauthenticated* path
strictly worse than not authenticating at all.

### 3. A second `RemoteGitCliAdapter` instance, its own cache root, not a `WorkspacePolicy` root

Spec-source clones land under `<storage.directory>/spec-source-cache/<id>`,
cloned by a second bean of the *same* `RemoteGitCliAdapter` class configured
with that root — reusing the class (fixed argv, `GIT_ASKPASS`, timeout/output
bounds) costs nothing new. Unlike the consumer remote-cache directory
(ADR-0007 decision #5), this cache root is deliberately **not** added to
`WorkspacePolicy`'s roots: a spec-source repository must never become
selectable as an analysable/patchable consumer repository just because it
happens to be a local git checkout on disk.

### 4. Specs are cloned lazily, on listing, mirroring how consumer repos already refresh

`RemoteRepositoryService.ensureLocalClone` clones-or-refreshes a *consumer*
repository at run-creation time, not at registration time, so it is always
current without a separate "refresh" concept. `SpecSourceService` follows
the identical pattern: registration only stores the row; `listSpecFiles()`
clones-or-refreshes every registered spec source and lists its files, called
whenever setup options are requested. Re-registering the same ID is already
how a consumer repository's credential gets rotated — spec sources get the
same behaviour for free. No dedicated refresh endpoint exists because
loading the run-setup screen already is one; this only becomes expensive if
a deployment registers many spec sources, which is out of scope for this
slice (see FR-039's larger fleet-registry vision for that case).

A clone failure for one registered source (revoked token, network blip)
never fails the whole setup listing — `SpecSourceService.listSpecFiles()`
catches per-source and simply omits that source's files, so one broken
registration doesn't take down run setup for everyone else.

### 5. Manual upload joins the same pool as local and spec-source files, not a separate flow

`POST /api/specs` (multipart) writes an uploaded file to
`<storage.directory>/uploaded-specs/<fileName>` — same-name re-upload
overwrites, which is the update mechanism (no versioning, no delete
endpoint; this is a local single-operator tool, matching the trust model
already accepted for the audit trail's principal and the CLI's blank-slate
ephemeral store). Uploaded files are listed exactly like the bundled local
directory: `Files.list` plus the same `.yaml`/`.yml`/`.json` filter. The
result is one combined pool for spec selection regardless of origin — local
bundled files, uploaded files, and files from every registered spec-source
repository — rather than three separate pickers the user has to reason
about.

### 6. A qualified spec ID disambiguates the combined pool, with the bare filename kept as a backward-compatible default

Once specs can come from three places, a bare filename is no longer
guaranteed unique (two spec-source repositories can both have
`openapi.yaml`). Every offered option is now `{id, label, origin,
sourceId}`, where `id` is a small qualified scheme: `local:<fileName>`,
`upload:<fileName>`, or `source:<sourceId>:<fileName>`. `RunService`'s spec
resolution parses the prefix; **a bare filename with no recognised prefix is
treated as `local:`** — the exact behaviour it already had. This keeps two
things working completely unchanged: the headless CLI/CI gate (FR-026,
`--contractguard.cli.old-spec=customer-api-v1.yaml`), and every already
-persisted `AnalysisRun` whose stored `oldSpecFile`/`newSpecFile` predates
this change. No `RunDocument` version bump was needed.

## Consequences

- Two more DDL copies (h2/postgresql), consistent with ADR-0006's accepted
  trade-off of vendor-specific migrations over a database-agnostic layer.
- Uploaded specs and spec-source clones are both process-wide, unscoped to
  any user — the same single-operator trust model as everything else in
  this codebase pending FR-024 (authentication). Multi-tenant isolation is
  explicitly out of scope here.
- A spec source with many files (a monorepo, say) offers all of them, not
  just OpenAPI-looking ones beyond the extension filter — no content
  sniffing is attempted. Acceptable for this slice; a real "is this actually
  an OpenAPI document" check would belong in the diff adapter's own parsing,
  which already rejects malformed specs when a run actually starts.
- No dedicated delete/deregister endpoint for spec sources or uploads in
  this slice, matching FR-027's own scope (consumer repositories cannot be
  deregistered either yet).
