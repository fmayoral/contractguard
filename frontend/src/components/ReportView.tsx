import { useState } from 'react';
import { marked } from 'marked';
import { api } from '../api';

interface ReportViewProps {
  runId: string;
  terminal: boolean;
}

export function ReportView({ runId, terminal }: ReportViewProps) {
  const [html, setHtml] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!terminal) {
    return <p className="hint">The report becomes available when the run finishes.</p>;
  }

  const load = async () => {
    setError(null);
    try {
      const markdown = await api.markdownReport(runId);
      setHtml(await marked.parse(markdown));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div>
      <div className="report-actions">
        {!html && <button onClick={load}>Load report</button>}
        <a href={api.reportMarkdownUrl(runId)} download={`contractguard-${runId}.md`}>
          Download Markdown
        </a>
        <a href={api.reportJsonUrl(runId)} download={`contractguard-${runId}.json`}>
          Download JSON
        </a>
      </div>
      {error && <p className="error">{error}</p>}
      {html && <div className="report-body" dangerouslySetInnerHTML={{ __html: html }} />}
    </div>
  );
}
