"use client";

import { useEffect, useState } from "react";
import { useWorkbenchStore } from "@/stores/workbenchStore";
import { useVariableParser } from "@/hooks/useVariableParser";
import type { VariableEntry } from "@/hooks/useVariableParser";

export default function WorkbenchClient() {
  const store = useWorkbenchStore();
  const [activeTab, setActiveTab] = useState<"rendered" | "output" | "diff">("rendered");

  useEffect(() => {
    store.loadSessions();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const variables = useVariableParser(
    store.yaml,
    store.fixtureInputs,
    store.editedValues,
  );

  const hasMissing = variables.some((v) => v.status === "missing");

  return (
    <div className="flex h-screen flex-col">
      {/* Header */}
      <header className="flex items-center justify-between border-b border-gray-800 px-6 py-3">
        <div className="flex items-center gap-4">
          <a href="/monitor" className="text-sm text-gray-400 hover:text-gray-200">
            Monitor
          </a>
          <h1 className="text-lg font-bold text-gray-100">Workbench</h1>
        </div>
        <div className="flex items-center gap-3">
          {/* Session Selector */}
          <select
            value={store.selectedSessionId ?? ""}
            onChange={(e) => e.target.value && store.selectSession(e.target.value)}
            className="rounded-md border border-gray-700 bg-gray-900 px-3 py-1.5 text-sm text-gray-200"
          >
            <option value="">세션 선택...</option>
            {store.sessions.map((s) => (
              <option key={s.sessionId} value={s.sessionId}>
                {s.sessionId.slice(0, 8)} ({s.steps.length} steps)
              </option>
            ))}
          </select>

          {/* Step Selector */}
          {store.selectedSessionId && (
            <select
              value={store.selectedStep ?? ""}
              onChange={(e) => e.target.value && store.selectStep(e.target.value)}
              className="rounded-md border border-gray-700 bg-gray-900 px-3 py-1.5 text-sm text-gray-200"
            >
              <option value="">step 선택...</option>
              {store.sessions
                .find((s) => s.sessionId === store.selectedSessionId)
                ?.steps.map((step) => (
                  <option key={step} value={step}>
                    {step}
                    {store.versions[step] ? ` (${store.versions[step]})` : ""}
                  </option>
                ))}
            </select>
          )}

          {/* Actions */}
          <button
            onClick={() => store.saveYaml()}
            disabled={!store.yamlDirty || store.status === "saving"}
            className="rounded-md bg-gray-700 px-3 py-1.5 text-sm text-gray-200 hover:bg-gray-600 disabled:opacity-40"
          >
            {store.status === "saving" ? "Saving..." : "Save YAML"}
          </button>
          <button
            onClick={() => store.runStep()}
            disabled={!store.selectedStep || store.status === "running" || hasMissing}
            className="rounded-md bg-brand-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-40"
          >
            {store.status === "running" ? "Running..." : "Run ▶"}
          </button>

          {/* Status */}
          <span className="text-xs text-gray-500">
            {store.status === "ready"
              ? "Ready"
              : store.status === "loading"
                ? "Loading..."
                : store.status === "saving"
                  ? "Saving..."
                  : "Running..."}
          </span>
        </div>
      </header>

      {/* Error Banner */}
      {store.error && (
        <div className="flex items-center justify-between bg-error-500/10 px-6 py-2 text-sm text-error-500">
          <span>{store.error}</span>
          <button onClick={() => store.clearError()} className="text-xs hover:underline">
            dismiss
          </button>
        </div>
      )}

      {/* 3-Zone Layout */}
      <div className="flex flex-1 overflow-hidden">
        {/* Zone 1: YAML Editor */}
        <section className="flex w-[40%] flex-col border-r border-gray-800">
          <div className="border-b border-gray-800 px-4 py-2">
            <span className="text-sm font-medium text-gray-300">YAML Editor</span>
          </div>
          <textarea
            value={store.yaml}
            onChange={(e) => store.updateYaml(e.target.value)}
            spellCheck={false}
            className="flex-1 resize-none bg-gray-950 p-4 font-mono text-sm text-gray-200 outline-none"
            placeholder="step을 선택하면 YAML이 로드됩니다..."
          />
        </section>

        {/* Zone 2: Variable Panel */}
        <section className="flex w-[25%] flex-col border-r border-gray-800">
          <div className="flex items-center justify-between border-b border-gray-800 px-4 py-2">
            <span className="text-sm font-medium text-gray-300">Variables</span>
            <button
              onClick={() => store.resetVariablesToFixture()}
              className="text-xs text-gray-500 hover:text-gray-300"
            >
              Reset to Fixture
            </button>
          </div>
          <div className="flex-1 overflow-y-auto p-4">
            <VariableList
              variables={variables}
              onUpdate={store.updateVariable}
            />
          </div>
        </section>

        {/* Zone 3: Output / Diff */}
        <section className="flex w-[35%] flex-col">
          <div className="flex gap-1 border-b border-gray-800 px-4 py-2">
            {(["rendered", "output", "diff"] as const).map((tab) => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`rounded px-3 py-1 text-sm capitalize ${
                  activeTab === tab
                    ? "bg-gray-800 text-gray-100"
                    : "text-gray-500 hover:text-gray-300"
                }`}
              >
                {tab}
              </button>
            ))}
          </div>
          <div className="flex-1 overflow-y-auto p-4">
            {activeTab === "rendered" && (
              <pre className="whitespace-pre-wrap font-mono text-sm text-gray-300">
                {store.fixtureOutput || "렌더링된 프롬프트가 여기에 표시됩니다"}
              </pre>
            )}
            {activeTab === "output" && (
              <div>
                {store.executionResult ? (
                  <div className="space-y-3">
                    <div className="flex gap-4 text-xs text-gray-500">
                      <span>{store.executionResult.model}</span>
                      <span>
                        {(store.executionResult.latencyMs / 1000).toFixed(1)}s
                      </span>
                      <span>
                        {store.executionResult.promptChars}→
                        {store.executionResult.responseChars} chars
                      </span>
                    </div>
                    {store.executionResult.versionWarning && (
                      <div className="rounded bg-warning-500/10 px-3 py-2 text-sm text-warning-400">
                        {store.executionResult.versionWarning}
                      </div>
                    )}
                    <pre className="whitespace-pre-wrap rounded bg-gray-950 p-3 font-mono text-sm text-gray-200">
                      {store.executionResult.response}
                    </pre>
                  </div>
                ) : (
                  <div className="text-sm text-gray-500">
                    Run Step을 실행하면 결과가 여기에 표시됩니다
                  </div>
                )}
              </div>
            )}
            {activeTab === "diff" && (
              <div className="text-sm text-gray-500">
                {store.executionResult && store.fixtureOutput ? (
                  <div className="space-y-4">
                    <div>
                      <h3 className="mb-1 text-xs font-semibold text-gray-400">
                        Fixture Output
                      </h3>
                      <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded bg-gray-950 p-3 font-mono text-xs text-gray-400">
                        {store.fixtureOutput}
                      </pre>
                    </div>
                    <div>
                      <h3 className="mb-1 text-xs font-semibold text-gray-400">
                        New Output
                      </h3>
                      <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded bg-gray-950 p-3 font-mono text-xs text-gray-200">
                        {store.executionResult.response}
                      </pre>
                    </div>
                  </div>
                ) : (
                  "Fixture output과 실행 결과가 있으면 비교가 표시됩니다"
                )}
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

function VariableList({
  variables,
  onUpdate,
}: {
  variables: VariableEntry[];
  onUpdate: (name: string, value: string) => void;
}) {
  if (variables.length === 0) {
    return (
      <div className="text-sm text-gray-500">
        YAML 템플릿에서 {"{{변수}}"} 가 감지되면 여기에 표시됩니다
      </div>
    );
  }

  const statusColors: Record<string, string> = {
    fixture: "bg-success-500",
    edited: "bg-warning-400",
    missing: "bg-error-500",
    unused: "bg-gray-600",
  };

  return (
    <div className="space-y-3">
      {variables.map((v) => (
        <div
          key={v.name}
          className={`rounded-lg border p-3 ${
            v.status === "unused"
              ? "border-gray-800 opacity-50"
              : v.status === "missing"
                ? "border-error-500/30"
                : "border-gray-800"
          }`}
        >
          <div className="mb-1.5 flex items-center gap-2">
            <span
              className={`h-2 w-2 rounded-full ${statusColors[v.status]}`}
            />
            <span className="font-mono text-xs font-medium text-gray-300">
              {v.name}
            </span>
            <span className="text-xs text-gray-600">
              {v.status === "fixture"
                ? "fixture"
                : v.status === "edited"
                  ? "edited"
                  : v.status === "missing"
                    ? "missing"
                    : "unused"}
            </span>
          </div>
          <textarea
            value={v.value}
            onChange={(e) => onUpdate(v.name, e.target.value)}
            rows={Math.min(v.value.split("\n").length, 5)}
            disabled={v.status === "unused"}
            className="w-full resize-none rounded border border-gray-800 bg-gray-950 px-2 py-1.5 font-mono text-xs text-gray-300 outline-none focus:border-brand-600 disabled:opacity-50"
          />
        </div>
      ))}
    </div>
  );
}
