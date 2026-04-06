"use client";

import { useState } from "react";
import { API_BASE } from "@/lib/api/client";

export default function LlmTestClient() {
  const [model, setModel] = useState("gemini-2.5-flash");
  const [thinkingBudget, setThinkingBudget] = useState(0);
  const [prompt, setPrompt] = useState("");
  const [response, setResponse] = useState("");
  const [meta, setMeta] = useState<{ model?: string; latencyMs?: number; inputTokens?: number; outputTokens?: number; costUsd?: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function send() {
    if (!prompt.trim()) return;
    setLoading(true);
    setError(null);
    setResponse("");
    setMeta(null);

    try {
      const res = await fetch(
        `${API_BASE}/admin/llm-test?model=${encodeURIComponent(model)}&thinkingBudget=${thinkingBudget}`,
        {
          method: "POST",
          headers: { "Content-Type": "text/plain" },
          body: prompt,
        },
      );
      if (!res.ok) {
        setError(`${res.status}: ${await res.text()}`);
        return;
      }
      const data = await res.json();
      setResponse(data.response ?? "");
      setMeta({
        model: data.model,
        latencyMs: data.latencyMs,
        inputTokens: data.inputTokens,
        outputTokens: data.outputTokens,
        costUsd: data.costUsd,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center gap-3 border-b border-gray-800 px-6 py-3">
        <a href="/monitor" className="text-sm text-gray-400 hover:text-gray-200">Monitor</a>
        <a href="/workbench" className="text-sm text-gray-400 hover:text-gray-200">Workbench</a>
        <a href="/chat-test" className="text-sm text-gray-400 hover:text-gray-200">Chat Test</a>
        <h1 className="text-lg font-bold text-gray-100">LLM Test</h1>
        <select
          value={model}
          onChange={(e) => setModel(e.target.value)}
          className="ml-4 rounded border border-gray-700 bg-gray-900 px-3 py-1.5 text-sm text-gray-200"
        >
          <option value="gemini-2.5-flash">Gemini 2.5 Flash</option>
          <option value="gemini-2.5-flash-lite">Gemini 2.5 Flash Lite</option>
          <option value="gemini-2.5-pro">Gemini 2.5 Pro</option>
          <option value="gpt-4.1-mini">GPT-4.1-mini</option>
          <option value="gpt-4.1">GPT-4.1</option>
          <option value="gpt-4o-mini">GPT-4o-mini</option>
          <option value="claude-sonnet-4-6">Claude Sonnet 4.6</option>
          <option value="claude-haiku-4-5">Claude Haiku 4.5</option>
        </select>
        <input
          type="number"
          value={thinkingBudget}
          onChange={(e) => setThinkingBudget(Number(e.target.value))}
          className="w-24 rounded border border-gray-700 bg-gray-900 px-3 py-1.5 text-sm text-gray-200"
          placeholder="thinking"
        />
        <button
          onClick={send}
          disabled={loading || !prompt.trim()}
          className="rounded bg-brand-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-40"
        >
          {loading ? "Running..." : "Run"}
        </button>
      </header>

      {error && (
        <div className="bg-error-500/10 px-6 py-2 text-sm text-error-500">{error}</div>
      )}

      <div className="flex flex-1 overflow-hidden">
        {/* Prompt */}
        <section className="flex w-1/2 flex-col border-r border-gray-800">
          <div className="border-b border-gray-800 px-4 py-2 text-sm font-medium text-gray-300">
            Prompt
          </div>
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            spellCheck={false}
            className="flex-1 resize-none bg-gray-950 p-4 font-mono text-sm text-gray-200 outline-none"
            placeholder="프롬프트를 입력하세요..."
          />
        </section>

        {/* Response */}
        <section className="flex w-1/2 flex-col">
          <div className="flex items-center justify-between border-b border-gray-800 px-4 py-2">
            <span className="text-sm font-medium text-gray-300">Response</span>
            {meta && (
              <div className="flex gap-3 text-xs text-gray-500">
                <span>{meta.model}</span>
                {meta.latencyMs != null && <span>{(meta.latencyMs / 1000).toFixed(1)}s</span>}
                {meta.inputTokens != null && <span>{meta.inputTokens}in</span>}
                {meta.outputTokens != null && <span>{meta.outputTokens}out</span>}
                {meta.costUsd != null && <span>${Number(meta.costUsd).toFixed(6)}</span>}
              </div>
            )}
          </div>
          <pre className="flex-1 overflow-y-auto whitespace-pre-wrap bg-gray-950 p-4 font-mono text-sm text-gray-200 scrollbar-hide">
            {response || (loading ? "thinking..." : "Run을 클릭하면 결과가 여기에 표시됩니다")}
          </pre>
        </section>
      </div>
    </div>
  );
}
