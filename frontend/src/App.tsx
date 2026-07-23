import { useEffect } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { applyTheme, initialTheme } from './theme';
import { DashboardPage } from './pages/DashboardPage';
import { NewRunPage } from './pages/NewRunPage';
import { RunDetailPage } from './pages/RunDetailPage';
import { RunsPage } from './pages/RunsPage';
import { SettingsPage } from './pages/SettingsPage';

function NavIcon({ path }: { path: string }) {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
      <path d={path} fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

const ICONS = {
  dashboard: 'M4 13h6V4H4v9zm0 7h6v-5H4v5zm10 0h6v-9h-6v9zm0-16v5h6V4h-6z',
  runs: 'M4 6h16M4 12h16M4 18h10',
  newRun: 'M12 5v14M5 12h14',
  settings:
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm7.4-3a7.4 7.4 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a7.5 7.5 0 0 0-2-1.2L14.6 3h-4l-.4 2.7a7.5 7.5 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5a7.4 7.4 0 0 0 0 2.4l-2 1.5 2 3.4 2.3-1a7.5 7.5 0 0 0 2 1.2l.4 2.7h4l.4-2.7a7.5 7.5 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z',
};

/** Application shell: a fixed navigation rail plus the routed page area. */
export default function App() {
  // The stored (or OS-preferred) theme must apply at startup, not only once the
  // Settings page -- the only place that changes it -- has been visited.
  useEffect(() => {
    applyTheme(initialTheme());
  }, []);

  return (
    <BrowserRouter>
      <div className="app-shell">
        <nav className="nav-rail" aria-label="Primary">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true">
              ⛨
            </span>
            <span className="brand-name">ContractGuard</span>
          </div>
          <NavLink to="/" end>
            <NavIcon path={ICONS.dashboard} />
            Dashboard
          </NavLink>
          <NavLink to="/runs">
            <NavIcon path={ICONS.runs} />
            Runs
          </NavLink>
          <NavLink to="/new">
            <NavIcon path={ICONS.newRun} />
            New run
          </NavLink>
          <NavLink to="/settings">
            <NavIcon path={ICONS.settings} />
            Settings
          </NavLink>
        </nav>
        <main className="app-content">
          <Routes>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/runs" element={<RunsPage />} />
            <Route path="/runs/:runId" element={<RunDetailPage />} />
            <Route path="/new" element={<NewRunPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}
