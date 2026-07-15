import { describe, expect, it } from 'vitest';
import { languageForPath, parseUnifiedDiff } from './diff';

const TWO_FILE_DIFF = [
  '--- a/src/main/java/com/example/A.java',
  '+++ b/src/main/java/com/example/A.java',
  '@@ -3,3 +3,3 @@',
  ' context line',
  '-    private String fullName;',
  '+    private String displayName;',
  '--- a/src/test/java/com/example/ATest.java',
  '+++ b/src/test/java/com/example/ATest.java',
  '@@ -10,2 +10,1 @@',
  '-removed one',
  '-removed two',
  '+added one',
  '\\ No newline at end of file',
  '',
].join('\r\n');

describe('parseUnifiedDiff', () => {
  it('splits a unified diff into per-file sections with counts', () => {
    const files = parseUnifiedDiff(TWO_FILE_DIFF);
    expect(files.map((f) => f.path)).toEqual([
      'src/main/java/com/example/A.java',
      'src/test/java/com/example/ATest.java',
    ]);
    expect(files[0].additions).toBe(1);
    expect(files[0].deletions).toBe(1);
    expect(files[1].additions).toBe(1);
    expect(files[1].deletions).toBe(2);
  });

  it('assigns git-style old/new line numbers from hunk headers', () => {
    const [first] = parseUnifiedDiff(TWO_FILE_DIFF);
    expect(first.lines.map((l) => [l.kind, l.oldLine, l.newLine])).toEqual([
      ['hunk', null, null],
      ['context', 3, 3],
      ['del', 4, null],
      ['add', null, 4],
    ]);
  });

  it('keeps trailing meta lines and returns nothing for an empty diff', () => {
    const files = parseUnifiedDiff(TWO_FILE_DIFF);
    expect(files[1].lines.at(-1)).toEqual({
      kind: 'meta',
      text: '\\ No newline at end of file',
      oldLine: null,
      newLine: null,
    });
    expect(parseUnifiedDiff('')).toEqual([]);
  });
});

describe('languageForPath', () => {
  it('maps known extensions to Prism grammar names', () => {
    expect(languageForPath('src/A.java')).toBe('java');
    expect(languageForPath('src/app.tsx')).toBe('typescript');
    expect(languageForPath('config/app.yml')).toBe('yaml');
    expect(languageForPath('pom.xml')).toBe('markup');
    expect(languageForPath('app.properties')).toBe('properties');
  });

  it('returns null for unknown or missing extensions', () => {
    expect(languageForPath('README.unknownext')).toBeNull();
    expect(languageForPath('Makefile')).toBeNull();
  });
});
