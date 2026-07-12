import { useEffect, useState } from 'react';
import { applyTheme, initialTheme, storeTheme, type Theme } from '../theme';

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(initialTheme);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  const next: Theme = theme === 'dark' ? 'light' : 'dark';
  return (
    <button
      type="button"
      className="theme-toggle"
      aria-label={`Switch to ${next} theme`}
      onClick={() => {
        setTheme(next);
        storeTheme(next);
      }}
    >
      {theme === 'dark' ? '☀ Light' : '● Dark'}
    </button>
  );
}
