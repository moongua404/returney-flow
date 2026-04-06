"use client";

import { useState, useRef, useEffect } from "react";
import { apiFetchJson } from "@/lib/api/client";

interface ChatMessage {
  role: "USER" | "ASSISTANT";
  content: string;
  meta?: {
    depthLevel?: string;
    hints?: string[];
    extractedFacts?: string[];
  };
}

interface SendResponse {
  assistantMessages: Array<{
    content: string;
    messageType: string;
    metaJson?: string;
  }>;
}

export default function ChatTestClient() {
  const [sessionId, setSessionId] = useState("");
  const [chapter, setChapter] = useState("CH2_0");
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  async function loadHistory() {
    if (!sessionId) return;
    setError(null);
    try {
      const data = await apiFetchJson<{ messages: Array<{ role: string; content: string; messageType: string; metaJson?: string }> }>(
        `/sessions/${sessionId}/chat/history?chapter=${chapter}`,
      );
      const history: ChatMessage[] = data.messages
        .filter((m) => m.messageType === "TEXT" || m.messageType === "CHAPTER_INTRO")
        .map((m) => {
          let meta;
          if (m.metaJson) {
            try { meta = JSON.parse(m.metaJson); } catch { /* */ }
          }
          return { role: m.role as "USER" | "ASSISTANT", content: m.content, meta };
        });
      setMessages(history);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load history");
    }
  }

  async function send() {
    if (!sessionId || !input.trim()) return;
    const userMsg = input.trim();
    setInput("");
    setMessages((prev) => [...prev, { role: "USER", content: userMsg }]);
    setLoading(true);
    setError(null);

    try {
      const data = await apiFetchJson<SendResponse>(
        `/sessions/${sessionId}/chat/messages`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ chapter, content: userMsg }),
        },
      );
      for (const msg of data.assistantMessages) {
        if (msg.messageType === "TEXT") {
          let meta: ChatMessage["meta"] | undefined;
          if (msg.metaJson) {
            try { meta = JSON.parse(msg.metaJson); } catch { /* */ }
          }
          setMessages((prev) => [...prev, { role: "ASSISTANT", content: msg.content, meta }]);
        }
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to send");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex h-screen flex-col">
      {/* Header */}
      <header className="flex items-center gap-3 border-b border-gray-800 px-6 py-3">
        <a href="/monitor" className="text-sm text-gray-400 hover:text-gray-200">Monitor</a>
        <a href="/workbench" className="text-sm text-gray-400 hover:text-gray-200">Workbench</a>
        <h1 className="text-lg font-bold text-gray-100">Chat Test</h1>
        <input
          value={sessionId}
          onChange={(e) => setSessionId(e.target.value)}
          placeholder="Session ID"
          className="ml-4 rounded border border-gray-700 bg-gray-900 px-3 py-1.5 font-mono text-xs text-gray-200 w-80"
        />
        <select
          value={chapter}
          onChange={(e) => setChapter(e.target.value)}
          className="rounded border border-gray-700 bg-gray-900 px-3 py-1.5 text-sm text-gray-200"
        >
          <option value="CH2_0">CH2_0</option>
          <option value="CH2_1">CH2_1</option>
          <option value="CH2_2">CH2_2</option>
          <option value="CH2_3">CH2_3</option>
          <option value="CH2_4">CH2_4</option>
        </select>
        <button
          onClick={loadHistory}
          className="rounded bg-gray-700 px-3 py-1.5 text-sm text-gray-200 hover:bg-gray-600"
        >
          Load
        </button>
      </header>

      {error && (
        <div className="bg-error-500/10 px-6 py-2 text-sm text-error-500">
          {error}
        </div>
      )}

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-6 space-y-4 scrollbar-hide">
        {messages.map((msg, i) => (
          <div key={i} className={`flex ${msg.role === "USER" ? "justify-end" : "justify-start"}`}>
            <div className={`max-w-[70%] rounded-xl px-4 py-3 ${
              msg.role === "USER"
                ? "bg-brand-600 text-white"
                : "bg-gray-800 text-gray-200"
            }`}>
              <pre className="whitespace-pre-wrap text-sm font-sans">{msg.content}</pre>
              {msg.meta && (
                <div className="mt-2 flex flex-wrap gap-2 text-xs">
                  {msg.meta.depthLevel && (
                    <span className="rounded bg-gray-700 px-1.5 py-0.5 text-gray-400">
                      {msg.meta.depthLevel}
                    </span>
                  )}
                  {msg.meta.hints?.map((h, j) => (
                    <span key={j} className="rounded bg-gray-700 px-1.5 py-0.5 text-gray-500">
                      {h}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}
        {loading && (
          <div className="flex justify-start">
            <div className="rounded-xl bg-gray-800 px-4 py-3 text-sm text-gray-400">
              thinking...
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <div className="border-t border-gray-800 px-6 py-4">
        <div className="flex gap-3">
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && !e.shiftKey && send()}
            placeholder="메시지를 입력하세요..."
            disabled={loading || !sessionId}
            className="flex-1 rounded-xl border border-gray-700 bg-gray-900 px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand-600 disabled:opacity-40"
          />
          <button
            onClick={send}
            disabled={loading || !sessionId || !input.trim()}
            className="rounded-xl bg-brand-600 px-6 py-2.5 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-40"
          >
            Send
          </button>
        </div>
      </div>
    </div>
  );
}
