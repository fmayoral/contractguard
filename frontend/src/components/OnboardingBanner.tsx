import { useState } from 'react';

const STORAGE_KEY = 'contractguard-onboarding-dismissed';

function isDismissed(): boolean {
  return localStorage.getItem(STORAGE_KEY) === 'true';
}

/** Shown once per browser until dismissed; points a first-time user at the three-step workflow. */
export function OnboardingBanner() {
  const [dismissed, setDismissed] = useState(isDismissed);

  if (dismissed) {
    return null;
  }

  const dismiss = () => {
    localStorage.setItem(STORAGE_KEY, 'true');
    setDismissed(true);
  };

  return (
    <section className="card onboarding-banner">
      <div className="card-header-row">
        <h2>New here? Start in three steps</h2>
        <button type="button" className="modal-close" aria-label="Dismiss the quick guide" onClick={dismiss}>
          ×
        </button>
      </div>
      <ol className="onboarding-steps">
        <li>
          <strong>Manage sources</strong> — register a GitHub repository, register a spec repository,
          or upload specification files.
        </li>
        <li>
          <strong>New analysis run</strong> — pick a consumer repository and the old and new
          specification, then start.
        </li>
        <li>
          <strong>Review &amp; approve</strong> — inspect the detected changes, consumer impact and
          migration plan, then approve to remediate and publish.
        </li>
      </ol>
    </section>
  );
}
