import { useEffect } from 'react';

interface HelpModalProps {
  onClose: () => void;
}

/** A quick reference for users who lose track of where they are in the workflow. */
export function HelpModal({ onClose }: HelpModalProps) {
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <header className="modal-header">
          <h2>Quick reference</h2>
          <button type="button" className="modal-close" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </header>

        <section className="modal-section">
          <h3>Sources vs. runs</h3>
          <p>
            <strong>Manage sources</strong> registers where repositories and specifications come
            from. <strong>New analysis run</strong> only picks among what is already registered —
            it never registers anything itself.
          </p>
        </section>

        <section className="modal-section">
          <h3>Repository kinds</h3>
          <p>
            <strong>Local workspace</strong> repositories already sit on disk next to the backend.{' '}
            <strong>Registered GitHub repositories</strong> are cloned on demand using a stored
            personal access token; that token needs at least pull (and push, if you plan to publish
            a remediation branch) permissions.
          </p>
        </section>

        <section className="modal-section">
          <h3>Specification kinds</h3>
          <p>
            A specification can be <strong>local</strong> (bundled with the workspace),{' '}
            <strong>uploaded</strong> (a file you dropped in through Manage sources), or come{' '}
            <strong>from a spec repository</strong> you registered — each shows up in its own group
            in the specification pickers.
          </p>
        </section>

        <section className="modal-section">
          <h3>Run lifecycle</h3>
          <p>
            A run diffs the old and new specification, assesses consumer impact, drafts a migration
            plan, and waits for your approval. Once approved you can execute the remediation, review
            validation results, and publish the working branch as a pull request.
          </p>
        </section>

        <section className="modal-section">
          <h3>Common issues</h3>
          <p>
            A run failing with <strong>DIRTY_REPOSITORY</strong> means the consumer repository has
            uncommitted changes — commit, stash or reset it, then execute again. If publishing fails
            with an authorization error, the registered repository&apos;s token is missing pull
            request permissions; remove and re-register it with a token that has them.
          </p>
        </section>

        <div className="modal-footer">
          <button type="button" onClick={onClose}>Done</button>
        </div>
      </div>
    </div>
  );
}
