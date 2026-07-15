export type DiffLineKind = 'add' | 'del' | 'context' | 'hunk' | 'meta';

export interface DiffLine {
  kind: DiffLineKind;
  /** Raw line including the leading marker character. */
  text: string;
  oldLine: number | null;
  newLine: number | null;
}

export interface FileDiff {
  path: string;
  additions: number;
  deletions: number;
  lines: DiffLine[];
}

const HUNK_HEADER = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

/** Splits a unified diff into per-file sections with git-style line numbers. */
export function parseUnifiedDiff(diff: string): FileDiff[] {
  const lines = diff.replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n');
  const files: FileDiff[] = [];
  let current: FileDiff | null = null;
  let oldNo = 0;
  let newNo = 0;

  for (const raw of lines) {
    if (raw.startsWith('--- ')) {
      continue;
    }
    if (raw.startsWith('+++ ')) {
      current = { path: raw.slice(4).replace(/^b\//, '').trim(), additions: 0, deletions: 0, lines: [] };
      files.push(current);
      continue;
    }
    if (!current) {
      continue;
    }
    const hunk = HUNK_HEADER.exec(raw);
    if (hunk) {
      oldNo = Number(hunk[1]);
      newNo = Number(hunk[2]);
      current.lines.push({ kind: 'hunk', text: raw, oldLine: null, newLine: null });
    } else if (raw.startsWith('\\')) {
      current.lines.push({ kind: 'meta', text: raw, oldLine: null, newLine: null });
    } else if (raw.startsWith('+')) {
      current.additions += 1;
      current.lines.push({ kind: 'add', text: raw, oldLine: null, newLine: newNo++ });
    } else if (raw.startsWith('-')) {
      current.deletions += 1;
      current.lines.push({ kind: 'del', text: raw, oldLine: oldNo++, newLine: null });
    } else {
      current.lines.push({ kind: 'context', text: raw, oldLine: oldNo++, newLine: newNo++ });
    }
  }
  return files;
}

const LANGUAGE_BY_EXTENSION: Record<string, string> = {
  java: 'java',
  ts: 'typescript',
  tsx: 'typescript',
  js: 'javascript',
  jsx: 'javascript',
  json: 'json',
  yml: 'yaml',
  yaml: 'yaml',
  xml: 'markup',
  html: 'markup',
  css: 'css',
  properties: 'properties',
};

/** Prism grammar name for a file path, or null when no highlighter applies. */
export function languageForPath(path: string): string | null {
  const dot = path.lastIndexOf('.');
  if (dot < 0) {
    return null;
  }
  return LANGUAGE_BY_EXTENSION[path.slice(dot + 1).toLowerCase()] ?? null;
}
