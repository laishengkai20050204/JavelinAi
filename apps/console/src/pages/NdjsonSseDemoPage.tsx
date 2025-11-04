import React from "react";

export default function NdjsonSseDemoPage() {
  const [userId, setUserId] = React.useState("u1");
  const [conversationId, setConversationId] = React.useState("c1");
  const [q, setQ] = React.useState("给我写一个两段式SSE示例说明");

  const [stepId, setStepId] = React.useState<string>("-");
  const [ndjsonLog, setNdjsonLog] = React.useState<string>("");
  const [sseLog, setSseLog] = React.useState<string>("");
  const [tokens, setTokens] = React.useState<string>("");

  const esRef = React.useRef<EventSource | null>(null);
  const abortRef = React.useRef<AbortController | null>(null);
  const accumulatedRef = React.useRef<string>("");

  const ndjsonPreRef = React.useRef<HTMLPreElement | null>(null);
  const ssePreRef = React.useRef<HTMLPreElement | null>(null);
  const tokensPreRef = React.useRef<HTMLPreElement | null>(null);

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
    <div className="space-y-4">
      <h3 className="text-lg font-semibold">NDJSON → stepId → SSE（极简示例）</h3>

      <div className="flex flex-wrap items-center gap-3">
        <label className="text-sm opacity-80">userId</label>
        <input value={userId} onChange={(e)=>setUserId(e.target.value)} className="rounded-lg border px-2 py-1 text-sm" size={10} />
        <label className="text-sm opacity-80">conversationId</label>
        <input value={conversationId} onChange={(e)=>setConversationId(e.target.value)} className="rounded-lg border px-2 py-1 text-sm" size={10} />
      </div>

      <div className="flex flex-col gap-2">
        <label className="text-sm opacity-80">问题：</label>
        <textarea value={q} onChange={(e)=>setQ(e.target.value)} rows={3} className="w-full rounded-lg border p-2" />
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <button onClick={handleRun} className="rounded-xl bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700">发送（NDJSON），并自动订阅 SSE</button>
        <button onClick={handleStop} className="rounded-xl border px-3 py-1.5 text-sm">关闭 SSE</button>
      </div>

      <div className="flex items-center gap-2 text-sm"><strong>stepId:</strong><span className="font-mono">{stepId}</span></div>

      <div className="space-y-2">
        <div>
          <div className="mb-1 text-sm font-medium">NDJSON 日志</div>
          <pre ref={ndjsonPreRef} className="rounded-xl bg-slate-900 p-3 text-slate-100 overflow-auto text-sm font-mono" style={{maxHeight: 280}}>{ndjsonLog}</pre>
        </div>
        <div>
          <div className="mb-1 text-sm font-medium">SSE token（message 事件，增量拼接）</div>
          <pre ref={tokensPreRef} className="rounded-xl bg-slate-900 p-3 text-slate-100 overflow-auto text-sm font-mono" style={{maxHeight: 280}}>{tokens}</pre>
        </div>
        <div>
          <div className="mb-1 text-sm font-medium">SSE 其它事件（decision / clientCalls / tools / status / finished / error）</div>
          <pre ref={ssePreRef} className="rounded-xl bg-slate-900 p-3 text-slate-100 overflow-auto text-sm font-mono" style={{maxHeight: 280}}>{sseLog}</pre>
        </div>
      </div>
    </div>
  );
}

