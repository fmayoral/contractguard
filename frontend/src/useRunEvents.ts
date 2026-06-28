import { useEffect, useRef, useState } from 'react';
import type { RunEvent } from './types';

/**
 * Live run timeline over SSE with automatic replay: the browser's EventSource
 * reconnects with Last-Event-ID, and the backend replays anything missed.
 * `onEvent` fires for every event so the page can refresh run state.
 */
export function useRunEvents(runId: string | null, onEvent: (event: RunEvent) => void) {
  const [events, setEvents] = useState<RunEvent[]>([]);
  const callbackRef = useRef(onEvent);
  callbackRef.current = onEvent;

  useEffect(() => {
    setEvents([]);
    if (!runId) {
      return;
    }
    const source = new EventSource(`/api/runs/${runId}/events`);
    const handle = (message: MessageEvent) => {
      const event = JSON.parse(message.data) as RunEvent;
      setEvents((existing) =>
        existing.some((e) => e.seq === event.seq) ? existing : [...existing, event],
      );
      callbackRef.current(event);
    };
    source.addEventListener('run-event', handle);
    return () => {
      source.removeEventListener('run-event', handle);
      source.close();
    };
  }, [runId]);

  return events;
}
