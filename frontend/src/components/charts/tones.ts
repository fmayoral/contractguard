// Chart segments always carry state meaning in this app (outcomes, classifications),
// so they wear the existing status tokens rather than a separate categorical palette.
// Segment ORDER is load-bearing, not cosmetic: the sequence ok -> warn -> muted ->
// bad -> accent (donut, wrap included) and bad -> muted -> warn -> ok (stacked bar)
// were validated for colour-vision-deficiency separation with the app's actual
// light/dark token values -- green and red must never touch (deutan dE ~4-6), and
// in light mode amber next to red collapses too. Reorder only with revalidation.
export type Tone = 'ok' | 'bad' | 'warn' | 'busy' | 'muted' | 'accent';

export function toneColor(tone: Tone): string {
  switch (tone) {
    case 'ok':
      return 'var(--ok)';
    case 'bad':
      return 'var(--bad)';
    case 'warn':
      return 'var(--warn)';
    case 'muted':
      return 'var(--muted)';
    default:
      return 'var(--accent)';
  }
}
