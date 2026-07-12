export type DiffLineKind = 'meta' | 'hunk' | 'add' | 'del' | 'context';

export function classifyDiffLine(line: string): DiffLineKind {
  if (line.startsWith('--- ') || line.startsWith('+++ ') || line.startsWith('diff ')
      || line.startsWith('index ') || line.startsWith('\\')) {
    return 'meta';
  }
  if (line.startsWith('@@')) {
    return 'hunk';
  }
  if (line.startsWith('+')) {
    return 'add';
  }
  if (line.startsWith('-')) {
    return 'del';
  }
  return 'context';
}

/** Renders a unified diff with per-line added/removed/hunk highlighting. */
export function DiffView({ diff }: { diff: string }) {
  const lines = diff.replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n');
  return (
    <pre className="diff">
      {lines.map((line, index) => (
        <span key={index} className={`diff-line diff-${classifyDiffLine(line)}`}>
          {line.length > 0 ? line : ' '}
          {'\n'}
        </span>
      ))}
    </pre>
  );
}
