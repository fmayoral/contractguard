import { afterEach, describe, expect, it, vi } from 'vitest';
import { applyTheme, initialTheme, storeTheme } from './theme';

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
  delete document.documentElement.dataset.theme;
});

describe('initialTheme', () => {
  it('prefers the stored choice over the OS preference', () => {
    localStorage.setItem('contractguard-theme', 'light');
    expect(initialTheme()).toBe('light');
  });

  it('follows the OS preference when nothing is stored', () => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }));
    expect(initialTheme()).toBe('light');
  });

  it('defaults to dark without stored choice or media query support', () => {
    expect(initialTheme()).toBe('dark');
  });
});

describe('applyTheme and storeTheme', () => {
  it('stamps the document root and persists the choice', () => {
    applyTheme('light');
    expect(document.documentElement.dataset.theme).toBe('light');
    storeTheme('light');
    expect(localStorage.getItem('contractguard-theme')).toBe('light');
  });
});
