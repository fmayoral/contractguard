import { useEffect, useRef } from 'react';

const BOTTOM_THRESHOLD_PX = 80;

/**
 * Keeps the page scrolled down as new content streams in for the selected
 * run -- new timeline events, a freshly populated plan, patches, etc. --
 * without fighting a user who has scrolled up to read something: scrolling
 * away from the bottom pauses auto-scroll until they scroll back down
 * themselves. Selecting a different run never jumps the page on its own;
 * only subsequent updates to the run already being watched do.
 */
export function useAutoScrollToBottom(runId: string | null, dependency: unknown) {
  const pinnedRef = useRef(true);
  const lastRunIdRef = useRef<string | null>(null);

  useEffect(() => {
    const onScroll = () => {
      const distanceFromBottom =
        document.documentElement.scrollHeight - window.scrollY - window.innerHeight;
      pinnedRef.current = distanceFromBottom <= BOTTOM_THRESHOLD_PX;
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const isNewRun = runId !== lastRunIdRef.current;
    lastRunIdRef.current = runId;
    if (isNewRun) {
      // Selecting a run starts pinned (new activity should be followed by
      // default) but never jumps the page just from opening it.
      pinnedRef.current = true;
      return;
    }
    if (!runId || !pinnedRef.current) {
      return;
    }
    window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, dependency]);
}
