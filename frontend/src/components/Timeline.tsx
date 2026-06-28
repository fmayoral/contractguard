import { eventKind, formatTimestamp } from '../format';
import type { RunEvent } from '../types';

interface TimelineProps {
  events: RunEvent[];
}

/** Trace timeline distinguishing tool, LLM, human and system steps. */
export function Timeline({ events }: TimelineProps) {
  if (events.length === 0) {
    return <p className="hint">Waiting for events…</p>;
  }
  return (
    <ol className="timeline">
      {events.map((event) => {
        const kind = eventKind(event.metadata);
        return (
          <li key={event.seq} className={`timeline-item kind-${kind}`}>
            <span className="timeline-time">{formatTimestamp(event.occurredAt)}</span>
            <span className={`timeline-kind kind-${kind}`}>{kind}</span>
            <span className="timeline-step">
              {event.step}/{event.status}
            </span>
            <span className="timeline-message">{event.message}</span>
          </li>
        );
      })}
    </ol>
  );
}
