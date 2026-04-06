"use client";

import { useCallback } from "react";
import { useMonitorStore } from "@/stores/monitorStore";
import { usePolling } from "@/hooks/usePolling";

export default function MonitorClient() {
  const { refreshAll, sessions, stats, selectedSessionId, selectSession } =
    useMonitorStore();

  const pollFn = useCallback(async () => {
    await refreshAll();
  }, [refreshAll]);

  usePolling(pollFn, { intervalMs: 5000 });

  return (
    <div className="flex h-screen flex-col">
      {/* Header */}
      <header className="flex items-center justify-between border-b border-gray-800 px-6 py-3">
        <div className="flex items-center gap-4">
          <h1 className="text-lg font-bold text-gray-100">Monitor</h1>
          <a href="/workbench" className="text-sm text-gray-400 hover:text-gray-200">
            Workbench
          </a>
        </div>
        {stats && (
          <div className="flex items-center gap-6 text-sm text-gray-400">
            <span>
              Total: ${stats.totalCostUsd.toFixed(4)}
            </span>
            <span>LLM Calls: {stats.totalLlmCalls}</span>
          </div>
        )}
      </header>

      {/* Body */}
      <div className="flex flex-1 overflow-hidden">
        {/* Session List */}
        <aside className="w-72 overflow-y-auto border-r border-gray-800 p-4">
          <h2 className="mb-3 text-sm font-semibold text-gray-400">Sessions</h2>
          <ul className="space-y-1">
            {sessions.map((s) => (
              <li key={s.sessionId}>
                <button
                  onClick={() => selectSession(s.sessionId)}
                  className={`w-full rounded-lg px-3 py-2 text-left text-sm transition ${
                    selectedSessionId === s.sessionId
                      ? "bg-gray-800 text-gray-100"
                      : "text-gray-400 hover:bg-gray-900 hover:text-gray-200"
                  }`}
                >
                  <div className="truncate font-mono text-xs">
                    {s.sessionId.slice(0, 8)}...
                  </div>
                  <div className="mt-1 flex items-center gap-2 text-xs text-gray-500">
                    <span>${s.totalCostUsd.toFixed(4)}</span>
                    <span>LLM {s.llmCallCount}</span>
                  </div>
                </button>
              </li>
            ))}
          </ul>
        </aside>

        {/* Pipeline Timeline */}
        <main className="flex-1 overflow-y-auto p-6">
          <PipelineTimeline />
        </main>
      </div>
    </div>
  );
}

function PipelineTimeline() {
  const { pipeline, selectedSessionId, expandedEvents, toggleEvent } =
    useMonitorStore();

  if (!selectedSessionId) {
    return (
      <div className="flex h-full items-center justify-center text-gray-500">
        세션을 선택하세요
      </div>
    );
  }

  if (pipeline.length === 0) {
    return (
      <div className="flex h-full items-center justify-center text-gray-500">
        파이프라인 이벤트가 없습니다
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {pipeline.map((event, i) => (
        <div key={i} className="rounded-lg border border-gray-800 bg-gray-900">
          <button
            onClick={() => toggleEvent(i)}
            className="flex w-full items-center gap-3 px-4 py-3 text-left text-sm"
          >
            <span
              className={`h-2 w-2 rounded-full ${
                event.success ? "bg-success-500" : "bg-error-500"
              }`}
            />
            <span className="rounded bg-gray-800 px-1.5 py-0.5 font-mono text-xs text-gray-400">
              {event.type}
            </span>
            <span className="flex-1 font-medium text-gray-200">
              {event.action}
            </span>
            {event.model && (
              <span className="text-xs text-gray-500">{event.model}</span>
            )}
            {event.latencyMs != null && (
              <span className="text-xs text-gray-500">
                {(event.latencyMs / 1000).toFixed(1)}s
              </span>
            )}
            {event.inputTokens != null && event.outputTokens != null && (
              <span className="text-xs text-gray-500">
                {event.inputTokens}→{event.outputTokens}
              </span>
            )}
          </button>
          {expandedEvents.has(i) && (
            <div className="border-t border-gray-800 px-4 py-3 text-xs">
              {event.errorMessage && (
                <div className="mb-2 text-error-500">
                  Error: {event.errorMessage}
                </div>
              )}
              {event.promptSnapshot && (
                <details className="mb-2">
                  <summary className="cursor-pointer text-gray-400">
                    Prompt
                  </summary>
                  <pre className="mt-1 max-h-60 overflow-auto whitespace-pre-wrap rounded bg-gray-950 p-2 text-gray-300">
                    {event.promptSnapshot}
                  </pre>
                </details>
              )}
              {event.responseSnapshot && (
                <details>
                  <summary className="cursor-pointer text-gray-400">
                    Response
                  </summary>
                  <pre className="mt-1 max-h-60 overflow-auto whitespace-pre-wrap rounded bg-gray-950 p-2 text-gray-300">
                    {event.responseSnapshot}
                  </pre>
                </details>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
