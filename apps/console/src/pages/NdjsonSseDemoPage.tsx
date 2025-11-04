import React from "react";
import { useSharedIds } from "../lib/sharedIds";
import { marked } from "marked";
import { markedHighlight } from "marked-highlight";
import hljs from "highlight.js";
import DOMPurify from "dompurify";

export default function NdjsonSseDemoPage() {
  const { userId, setUserId, conversationId, setConversationId } = useSharedIds("u1", "c1");
  const [q, setQ] = React.useState("给我写一个两段式SSE示例说明");

  const [stepId, setStepId] = React.useState<string>("-");
  const [ndjsonLog, setNdjsonLog] = React.useState<string>("");
  const [sseLog, setSseLog] = React.useState<string>("");
  const [tokens, setTokens] = React.useState<string>("");
  const [mdView, setMdView] = React.useState<boolean>(true);
  const [highlightOn, setHighlightOn] = React.useState<boolean>(false);
  const hlOnRef = React.useRef<boolean>(false);
  React.useEffect(() => { hlOnRef.current = highlightOn; }, [highlightOn]);

  const esRef = React.useRef<EventSource | null>(null);
  const abortRef = React.useRef<AbortController | null>(null);
  const accumulatedRef = React.useRef<string>("");

  const ndjsonPreRef = React.useRef<HTMLPreElement | null>(null);
  const ssePreRef = React.useRef<HTMLPreElement | null>(null);
  const tokensPreRef = React.useRef<HTMLDivElement | null>(null);

  // configure marked (once)
  React.useEffect(() => {
    marked.setOptions({ gfm: true, breaks: true });
    marked.use(markedHighlight({
      langPrefix: 'hljs language-',
      highlight(code, lang) {
        // 流式默认不高亮，避免出现大量 <span class="hljs-...">；只有在用户开启“高亮”且显式声明语言时才进行着色
        try {
          if (hlOnRef.current && lang && hljs.getLanguage(lang)) {
            return hljs.highlight(code, { language: lang }).value;
          }
          // 纯文本转义
          return code
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;');
        } catch { return code; }
      }
    }));
  }, []);

  function stripHljsArtifacts(input: string): string {
    if (!input) return input;
    let s = input;
    // Remove real <span class="hljs-..."> wrappers
    s = s.replace(/<\/?span[^>]*hljs[^>]*>/gi, "");
    // Remove encoded &lt;span class="hljs-..."&gt; wrappers
    s = s.replace(/&lt;\/?span[^&]*hljs[^&]*&gt;/gi, "");
    return s;
  }

  const renderedHtml = React.useMemo(() => {
    try {
      const source = stripHljsArtifacts(tokens ?? "");
      const html = marked.parse(source);
      return DOMPurify.sanitize(html);
    } catch {
      const source = stripHljsArtifacts(tokens ?? "");
      return DOMPurify.sanitize(source.replace(/\n/g, "<br/>"));
    }
  }, [tokens]);

  const scrollToBottom = (el: HTMLElement | null) => {
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  };

  function logNdjson(line: string) {
    setNdjsonLog((prev) => prev + line + "\n");
    setTimeout(() => scrollToBottom(ndjsonPreRef.current), 0);
  }
  function logSse(line: string) {
    setSseLog((prev) => prev + line + "\n");
    setTimeout(() => scrollToBottom(ssePreRef.current), 0);
  }

  function startSSE(step: string) {
    if (esRef.current) { esRef.current.close(); esRef.current = null; }
    accumulatedRef.current = "";
    setTokens("");

    const url = `/ai/v2/chat/sse?stepId=${encodeURIComponent(step)}`;
    const es = new EventSource(url);
    esRef.current = es;
    logSse(`[open] ${url}`);

    es.onmessage = (e) => {
      if (e.data === '[DONE]') { logSse('[message] [DONE]'); return; }
      try {
        const obj = JSON.parse(e.data);
        const delta = obj?.choices?.[0]?.delta ?? {};
        if (delta?.content) {
          accumulatedRef.current += delta.content;
          setTokens(accumulatedRef.current);
          setTimeout(() => scrollToBottom(tokensPreRef.current), 0);
        }
        logSse(`[message] ${e.data}`);
      } catch {
        logSse(`[message/raw] ${e.data}`);
      }
    };
    es.onerror = (e) => {
      try { logSse(`[error] ${JSON.stringify(e)}`); } catch { logSse('[error] <event>'); }
    };
    for (const name of ['decision','clientCalls','tools','status','finished','error'] as const) {
      es.addEventListener(name, (e) => {
        logSse(`[${name}] ${e.data || ''}`);
      });
    }
  }

  async function postNdjson(body: any) {
    const url = '/ai/v3/chat/step/ndjson';
    const ac = new AbortController();
    abortRef.current = ac;
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'accept': 'application/x-ndjson' },
      body: JSON.stringify(body),
      signal: ac.signal,
    });
    if (!res.ok || !res.body) {
      const t = await res.text().catch(()=>'');
      throw new Error(`NDJSON HTTP ${res.status}: ${t}`);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (!line) continue;
        logNdjson(line);
        try {
          const obj = JSON.parse(line);
          if (obj?.event === 'started' && obj?.data?.stepId) {
            const sid = String(obj.data.stepId);
            setStepId(sid);
            startSSE(sid);
          }
        } catch {}
      }
    }
    if (buf.trim()) logNdjson(buf.trim());
  }

  const handleRun = async () => {
    setNdjsonLog("");
    setSseLog("");
    setTokens("");
    setStepId("-");
    if (esRef.current) { esRef.current.close(); esRef.current = null; }
    if (abortRef.current) { abortRef.current.abort(); abortRef.current = null; }
    const body = { userId, conversationId, q };
    try { await postNdjson(body); } catch (e: any) { logNdjson(`[error] ${e?.message || String(e)}`); }
  };

  const handleStop = () => {
    if (esRef.current) { esRef.current.close(); esRef.current = null; logSse('[closed by user]'); }
    if (abortRef.current) { abortRef.current.abort(); abortRef.current = null; logNdjson('[NDJSON aborted by user]'); }
  };

  return (
    <div className="space-y-6">
      <div className="mb-2 text-base font-semibold">NDJSON → stepId → SSE（极简示例）</div>

      {/* Form 区域：保持与其他页面一致的 Field/网格风格 */}
      <Section title="">
        <div className="grid gap-4 md:grid-cols-3">
          <Field label="用户 ID">
            <input value={userId} onChange={(e)=>setUserId(e.target.value)} placeholder="u1" className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100" />
          </Field>
          <Field label="会话 ID">
            <input value={conversationId} onChange={(e)=>setConversationId(e.target.value)} placeholder="c1" className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100" />
          </Field>
          <div className="flex items-end gap-2">
            <button onClick={handleRun} className="inline-flex items-center gap-2 rounded-xl bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400">发送（NDJSON），并自动订阅 SSE</button>
            <button onClick={handleStop} className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700">关闭 SSE</button>
          </div>
        </div>
      </Section>

      <Section title="问题">
        <textarea value={q} onChange={(e)=>setQ(e.target.value)} rows={3} className="w-full rounded-xl border border-slate-300 bg-white p-3 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100" />
      </Section>

      <div className="flex items-center gap-2 text-sm"><strong>stepId:</strong><span className="font-mono">{stepId}</span></div>

      <div className="grid gap-4 md:grid-cols-1">
        <div className="rounded-2xl border bg-white p-3 shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <div className="mb-1 text-sm font-medium">NDJSON 日志</div>
          <pre ref={ndjsonPreRef} className="rounded-xl bg-slate-900 p-3 text-slate-100 overflow-auto text-sm font-mono whitespace-pre-wrap break-words" style={{maxHeight: 280}}>{ndjsonLog}</pre>
        </div>
        <div className="rounded-2xl border bg-white p-3 shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <div className="mb-2 flex items-center justify-between">
            <div className="text-sm font-medium">SSE token（message 事件，增量拼接）</div>
            <div className="inline-flex items-center gap-1 rounded-xl border border-slate-300 bg-white p-1 text-xs dark:border-slate-700 dark:bg-slate-800">
              <button onClick={() => setMdView(false)} className={`px-2 py-0.5 rounded-lg ${!mdView ? 'bg-slate-200 dark:bg-slate-700' : ''}`}>Raw</button>
              <button onClick={() => setMdView(true)} className={`px-2 py-0.5 rounded-lg ${mdView ? 'bg-slate-200 dark:bg-slate-700' : ''}`}>Markdown</button>
              {mdView && (
                <>
                  <span className="mx-1 opacity-40">|</span>
                  <button onClick={() => setHighlightOn((v)=>!v)} className={`px-2 py-0.5 rounded-lg ${highlightOn ? 'bg-slate-200 dark:bg-slate-700' : ''}`}>高亮</button>
                </>
              )}
            </div>
          </div>
          <div ref={tokensPreRef} className="rounded-xl overflow-auto" style={{maxHeight: 280}}>
            {mdView ? (
              <div className="prose prose-sm dark:prose-invert max-w-none">
                {/* eslint-disable-next-line react/no-danger */}
                <div dangerouslySetInnerHTML={{ __html: renderedHtml }} />
              </div>
            ) : (
              <pre className="bg-slate-900 p-3 text-slate-100 text-sm font-mono whitespace-pre-wrap break-words">{tokens}</pre>
            )}
          </div>
        </div>
        <div className="rounded-2xl border bg-white p-3 shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <div className="mb-1 text-sm font-medium">SSE 其它事件（decision / clientCalls / tools / status / finished / error）</div>
          <pre ref={ssePreRef} className="rounded-xl bg-slate-900 p-3 text-slate-100 overflow-auto text-sm font-mono whitespace-pre-wrap break-words" style={{maxHeight: 280}}>{sseLog}</pre>
        </div>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="py-2">
      {title && <div className="mb-2 text-sm font-medium text-slate-700 dark:text-slate-200">{title}</div>}
      {children}
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block text-sm">
      <div className="mb-1 text-slate-500 dark:text-slate-400">{label}</div>
      {children}
    </label>
  );
}
