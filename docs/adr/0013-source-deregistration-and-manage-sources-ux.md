# ADR-0013: Source Deregistration and the Manage-Sources UX Split

- Status: accepted
- Date: 2026-07-21

## Context

FR-027 and FR-043 gave a user three ways to register something (a remote
consumer repository, a spec-source repository, an uploaded specification)
but no way to un-register one. A registration made with the wrong clone URL,
or a token missing the permissions the workflow actually needs (pull request
creation, in particular), stayed listed forever — the only fix was a direct
database edit. That gap surfaced immediately on real use: an operator's
personal access token lacked PR permissions, and clearing the resulting bad
registrations required exactly that kind of manual intervention.

Separately, FR-043 folded registration, upload and run creation into one
3-step wizard (repository → specifications → review) on the theory that more
choices needed more structure. In practice a returning user who just wants
to start another run against sources they already registered had to click
through the same wizard shell every time, and a user who wanted to register
something *new* had no dedicated place to do it outside of starting a run.
The two activities — curating what's registered, and starting a run against
what's already there — have different frequencies and different failure
modes, and forcing them through one linear flow served neither well.

## Decisions

### 1. Deregistration deletes the registration row and the local clone cache together

`RemoteRepositoryRegistry.deregister(repositoryId)` and
`SpecSourceRegistry.deregister(repositoryId)` each do a plain `DELETE FROM`
their own table — no soft-delete, no `kind` discriminator, consistent with
ADR-0012 decision #1's choice to keep the two registries fully separate. The
service layer then also calls the new `RemoteGitPort.deleteLocalClone`,
because leaving the old clone on disk would let a corrected re-registration
(same repository ID, fixed URL or token) silently keep talking to the wrong
remote — the clone is keyed by repository ID, not by URL, so nothing else
would ever notice the mismatch. `RunService.deleteUploadedSpecification`
mirrors the same idea for uploads: it just deletes the file, since an
uploaded spec has no associated remote clone.

Deregistering (or deleting) something never registered is a no-op, not an
error — `deleteLocalClone` checks `Files.isDirectory` before walking, and
the SQL `DELETE` naturally affects zero rows. All three surface as `DELETE`
endpoints returning `204` unconditionally, matching that no-op-is-success
semantics.

### 2. `RemoteGitCliAdapter.deleteLocalClone` clears the read-only bit before deleting each file

Git marks loose object files (`.git/objects/**`) read-only. On POSIX, a
writable parent directory is enough to unlink a read-only file regardless of
the file's own permissions; NTFS enforces the file's own read-only attribute
independently, so `Files.deleteIfExists` on a fresh Windows clone's object
files fails outright. The fix is `path.toFile().setWritable(true)`
immediately before each delete, walked in `Comparator.reverseOrder()` so
directories empty out before removal. This was found by writing the test for
this feature, not by production failure: `deleteLocalCloneRemovesTheCacheDirectory`
failed with a leftover `.git/objects/**` file and a caught, logged
`IOException`, which is the same defensive catch-and-warn `deleteLocalClone`
already needed for any other filesystem oddity — a failed cleanup logs and
returns rather than failing the whole deregistration, since the registration
row is already gone at that point and a partially-cleaned cache directory is
a cosmetic problem, not a correctness one.

### 3. The 3-step wizard is replaced by a single-panel form plus a separate "Manage sources" modal

`RunSetup` is now one flat form — run name, repository, old spec, new spec,
a **Start analysis** button — with no forward/back navigation and no step
state to keep synchronised. All registration, upload and removal actions
(for all three kinds) move into a single `ManageSourcesModal`, opened by a
button next to the form and closed explicitly (Escape, an ✕, or **Done**).
Nothing about opening or closing the modal touches the run-creation fields
already chosen — `RunSetup` only re-fetches `/api/setup` and fills in
whichever picker is *still empty*, exactly the "never overwrite a choice
already made" rule FR-043 established for auto-filling a freshly uploaded
spec.

This is a straight product-direction call, not a technical constraint: nesting
registration inside run creation was actively worse for both of the two
activities it served, matching the product philosophy of building one
coherent, evolving surface rather than accreting wizard steps whenever a new
registration kind needed a place to live.

### 4. The setup DTO grows a `remoteRepositories: RemoteRepositorySummary[]`, replacing bare `String` IDs

The Manage Sources UI needs to show owner/name next to each registered
repository (so a user can tell two similarly-named registrations apart
before removing one) and needs the exact repository ID to call `DELETE`
against. `SetupOptions.remoteRepositories` therefore changed from `List
<String>` to the same `RemoteRepositorySummary` shape `specSources` already
used, via a new `DtoMapper.toRemoteRepositorySummary`. This is a breaking
shape change to `GET /api/setup`, accepted because the endpoint is
UI-internal (not part of the FR-026 CLI contract, which only reads spec
files) and the frontend is the only consumer.

### 5. Onboarding banner and quick-reference panel are presentation-only, dismissed state kept in `localStorage`

A first-run banner above `RunSetup` names the three-step workflow (Manage
sources → New analysis run → Review & approve); dismissing it sets a
`localStorage` flag so it never reappears in that browser, mirroring the
existing theme-preference persistence pattern (`theme.ts`). A **Help**
button opens a static quick-reference modal (source kinds, run lifecycle,
common failure remediation) with no server round-trip — it's fixed content
describing the fixed workflow, not data that needs to stay in sync with
anything. Neither touches application state or the API surface.

## Consequences

- Deregistration is still a single-operator, unauthenticated action —
  anyone who can reach the API can remove any registration — the same trust
  model FR-024 (authentication) is meant to eventually close, consistent
  with every other write path in this codebase today.
- `GET /api/setup`'s response shape changed; any external tooling that
  happened to depend on `remoteRepositories` being bare strings would break.
  None exists today (the CLI gate does not call this endpoint).
- The wizard's per-step validation (e.g., "you can't reach step 3 without a
  repository chosen") is gone; the single-panel form instead just disables
  **Start analysis** until all three selections are non-empty, which is
  simpler and was already how the wizard's final step worked.
