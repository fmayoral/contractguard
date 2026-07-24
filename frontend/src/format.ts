// Presentation logic kept pure for testability.

export const ACTIVE_STATES = [
  'CREATED',
  'VALIDATING_INPUT',
  'DIFFING',
  'SEARCHING',
  'ASSESSING',
  'PLANNING',
  'AWAITING_APPROVAL',
  'PREPARING_BRANCH',
  'PATCHING',
  'VALIDATING',
  'REPAIRING',
];

export function isTerminal(state: string): boolean {
  return ['SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED', 'PUBLISHED', 'PUBLISH_FAILED'].includes(
    state,
  );
}

export function stateTone(state: string): 'ok' | 'bad' | 'warn' | 'busy' {
  if (state === 'SUCCEEDED' || state === 'PUBLISHED') return 'ok';
  if (state === 'FAILED' || state === 'PUBLISH_FAILED') return 'bad';
  if (state === 'REJECTED' || state === 'CANCELLED') return 'warn';
  if (state === 'AWAITING_APPROVAL') return 'warn';
  return 'busy';
}

export function classificationTone(classification: string): 'ok' | 'bad' | 'warn' | 'busy' {
  switch (classification) {
    case 'BREAKING':
      return 'bad';
    case 'POTENTIALLY_BREAKING':
      return 'warn';
    case 'NON_BREAKING':
      return 'ok';
    default:
      return 'busy';
  }
}

export function severityTone(severity: string): 'ok' | 'bad' | 'warn' | 'busy' {
  switch (severity) {
    case 'HIGH':
      return 'bad';
    case 'MEDIUM':
      return 'warn';
    default:
      return 'ok';
  }
}

/** Kind flag from event metadata JSON: tool, llm, human or system. */
export function eventKind(metadata: string | null): string {
  if (!metadata) return 'system';
  try {
    const parsed = JSON.parse(metadata);
    return typeof parsed.kind === 'string' ? parsed.kind : 'system';
  } catch {
    return 'system';
  }
}

export function formatDuration(millis: number): string {
  if (millis < 1000) return `${millis} ms`;
  const seconds = millis / 1000;
  if (seconds < 60) return `${seconds.toFixed(1)} s`;
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  return `${minutes}m ${rest}s`;
}

export function formatTimestamp(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleTimeString(undefined, { hour12: false });
}

/** Date + time for lists that can span more than one day (unlike a single run's own timeline,
 * where the date is always implicit). */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** Human label for a change row: endpoint or schema.property. */
export function changeTarget(change: {
  method: string | null;
  path: string | null;
  schema: string | null;
  property: string | null;
}): string {
  if (change.path) {
    return `${change.method ?? ''} ${change.path}`.trim();
  }
  if (change.schema) {
    return change.property ? `${change.schema}.${change.property}` : change.schema;
  }
  return '—';
}

export function shortHash(hash: string): string {
  return hash.length > 12 ? hash.slice(0, 12) : hash;
}
