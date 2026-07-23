import { useNavigate } from 'react-router-dom';
import { OnboardingBanner } from '../components/OnboardingBanner';
import { RunSetup } from '../components/RunSetup';

/** Run creation as its own focused page; a created run navigates straight to its detail. */
export function NewRunPage() {
  const navigate = useNavigate();
  return (
    <div className="page narrow">
      <header className="page-header">
        <h2>New analysis run</h2>
        <p className="muted">Pick a consumer repository and two specification versions to compare.</p>
      </header>
      <OnboardingBanner />
      <RunSetup onCreated={(runId) => navigate(`/runs/${runId}`)} />
    </div>
  );
}
