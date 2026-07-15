import { Fragment, type ReactNode } from 'react';
import Prism from 'prismjs';
import 'prismjs/components/prism-java';
import 'prismjs/components/prism-json';
import 'prismjs/components/prism-yaml';
import 'prismjs/components/prism-typescript';
import 'prismjs/components/prism-properties';
import { languageForPath, parseUnifiedDiff, type DiffLine, type FileDiff } from '../diff';

type TokenStream = string | Prism.Token | (string | Prism.Token)[];

function tokenNodes(stream: TokenStream): ReactNode {
  if (typeof stream === 'string') {
    return stream;
  }
  if (Array.isArray(stream)) {
    return stream.map((part, index) => <Fragment key={index}>{tokenNodes(part)}</Fragment>);
  }
  const aliases = Array.isArray(stream.alias) ? stream.alias : stream.alias ? [stream.alias] : [];
  return (
    <span className={['token', stream.type, ...aliases].join(' ')}>
      {tokenNodes(stream.content as TokenStream)}
    </span>
  );
}

function highlight(code: string, language: string | null): ReactNode {
  const grammar = language ? Prism.languages[language] : undefined;
  return grammar ? tokenNodes(Prism.tokenize(code, grammar)) : code;
}

function DiffRow({ line, language }: { line: DiffLine; language: string | null }) {
  if (line.kind === 'hunk' || line.kind === 'meta') {
    return (
      <div className={`diff-row diff-${line.kind}`}>
        <span className="diff-gutter" />
        <span className="diff-gutter" />
        <span className="diff-code">{line.text}</span>
      </div>
    );
  }
  const marker = line.kind === 'add' ? '+' : line.kind === 'del' ? '−' : ' ';
  return (
    <div className={`diff-row diff-${line.kind}`}>
      <span className="diff-gutter">{line.oldLine ?? ''}</span>
      <span className="diff-gutter">{line.newLine ?? ''}</span>
      <span className="diff-code">
        <span className="diff-marker">{marker}</span>
        {highlight(line.text.slice(1), language)}
      </span>
    </div>
  );
}

function FileSection({ file }: { file: FileDiff }) {
  const language = languageForPath(file.path);
  return (
    <section className="diff-file">
      <header className="diff-file-header">
        <code>{file.path}</code>
        <span className="diff-stats">
          <span className="diff-stat-add">+{file.additions}</span>{' '}
          <span className="diff-stat-del">−{file.deletions}</span>
        </span>
      </header>
      <div className="diff-body">
        {file.lines.map((line, index) => (
          <DiffRow key={index} line={line} language={language} />
        ))}
      </div>
    </section>
  );
}

/** Git-style per-file diff preview with syntax highlighting where a grammar applies. */
export function DiffView({ diff }: { diff: string }) {
  const files = parseUnifiedDiff(diff);
  if (files.length === 0) {
    return <p className="hint">Empty diff.</p>;
  }
  return (
    <div className="diff">
      {files.map((file) => (
        <FileSection key={file.path} file={file} />
      ))}
    </div>
  );
}
