// ── Monitor Types ──

export interface SessionSummary {
  sessionId: string;
  totalCostUsd: number;
  llmCallCount: number;
  githubCallCount: number;
  lastCallAt: string;
}

export interface AdminStats {
  totalCostUsd: number;
  totalLlmCalls: number;
  byModel: Record<
    string,
    {
      costUsd: number;
      calls: number;
      inputTokens: number;
      outputTokens: number;
    }
  >;
}

export interface PipelineEvent {
  type: "llm" | "github";
  action: string;
  success: boolean;
  model?: string;
  inputTokens?: number;
  outputTokens?: number;
  costUsd?: number;
  statusCode?: number;
  latencyMs?: number;
  createdAt: string;
  errorMessage?: string;
  variablesSnapshot?: string;
  promptSnapshot?: string;
  responseSnapshot?: string;
}

export interface PipelineResponse {
  sources?: Array<{ name: string; type: string; size: number }>;
  events: PipelineEvent[];
}

// ── Workbench Types ──

export interface FixtureSession {
  sessionId: string;
  createdAt: string;
  steps: string[];
}

export interface ExecutionResult {
  response: string;
  model: string;
  latencyMs: number;
  promptChars: number;
  responseChars: number;
  inputTokens?: number;
  outputTokens?: number;
  step: string;
  versionWarning?: string;
}

export interface VersionStatus {
  [step: string]: string;
}
