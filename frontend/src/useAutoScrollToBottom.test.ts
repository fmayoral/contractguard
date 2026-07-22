import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useAutoScrollToBottom } from './useAutoScrollToBottom';

function setScrollGeometry(scrollHeight: number, scrollY: number, innerHeight: number) {
  Object.defineProperty(document.documentElement, 'scrollHeight', { value: scrollHeight, configurable: true });
  Object.defineProperty(window, 'scrollY', { value: scrollY, configurable: true });
  Object.defineProperty(window, 'innerHeight', { value: innerHeight, configurable: true });
}

describe('useAutoScrollToBottom', () => {
  let scrollToSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    scrollToSpy = vi.fn();
    window.scrollTo = scrollToSpy;
    setScrollGeometry(1000, 900, 100); // near the bottom (distance 0)
  });

  it('does not scroll just from selecting a run', () => {
    renderHook(({ runId, dep }) => useAutoScrollToBottom(runId, dep), {
      initialProps: { runId: 'run-1', dep: 'a' },
    });
    expect(scrollToSpy).not.toHaveBeenCalled();
  });

  it('scrolls to the bottom when new content arrives while pinned', () => {
    const { rerender } = renderHook(({ runId, dep }) => useAutoScrollToBottom(runId, dep), {
      initialProps: { runId: 'run-1', dep: 'a' },
    });
    rerender({ runId: 'run-1', dep: 'b' });
    expect(scrollToSpy).toHaveBeenCalledWith({ top: 1000, behavior: 'smooth' });
  });

  it('stops auto-scrolling once the user scrolls away from the bottom', () => {
    const { rerender } = renderHook(({ runId, dep }) => useAutoScrollToBottom(runId, dep), {
      initialProps: { runId: 'run-1', dep: 'a' },
    });

    setScrollGeometry(1000, 0, 100); // now far from the bottom
    window.dispatchEvent(new Event('scroll'));
    rerender({ runId: 'run-1', dep: 'b' });

    expect(scrollToSpy).not.toHaveBeenCalled();
  });

  it('resumes auto-scrolling once the user scrolls back near the bottom', () => {
    const { rerender } = renderHook(({ runId, dep }) => useAutoScrollToBottom(runId, dep), {
      initialProps: { runId: 'run-1', dep: 'a' },
    });

    setScrollGeometry(1000, 0, 100);
    window.dispatchEvent(new Event('scroll'));
    rerender({ runId: 'run-1', dep: 'b' });
    expect(scrollToSpy).not.toHaveBeenCalled();

    setScrollGeometry(1000, 900, 100);
    window.dispatchEvent(new Event('scroll'));
    rerender({ runId: 'run-1', dep: 'c' });
    expect(scrollToSpy).toHaveBeenCalledWith({ top: 1000, behavior: 'smooth' });
  });

  it('resets to pinned on a newly selected run without jumping the page immediately', () => {
    const { rerender } = renderHook(({ runId, dep }) => useAutoScrollToBottom(runId, dep), {
      initialProps: { runId: 'run-1', dep: 'a' },
    });

    setScrollGeometry(1000, 0, 100);
    window.dispatchEvent(new Event('scroll'));
    rerender({ runId: 'run-1', dep: 'b' });
    expect(scrollToSpy).not.toHaveBeenCalled();

    rerender({ runId: 'run-2', dep: 'x' });
    expect(scrollToSpy).not.toHaveBeenCalled();

    rerender({ runId: 'run-2', dep: 'y' });
    expect(scrollToSpy).toHaveBeenCalledWith({ top: 1000, behavior: 'smooth' });
  });
});
