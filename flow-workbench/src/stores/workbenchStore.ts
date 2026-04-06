import { create } from "zustand";
import { apiFetchJson, apiFetch } from "@/lib/api/client";
import type {
  FixtureSession,
  ExecutionResult,
  VersionStatus,
} from "@/types";

interface WorkbenchState {
  // 선택
  sessions: FixtureSession[];
  selectedSessionId: string | null;
  selectedStep: string | null;

  // 데이터
  yaml: string;
  yamlDirty: boolean;
  fixtureInputs: Record<string, string>;
  editedValues: Record<string, string>;
  fixtureOutput: string;
  executionResult: ExecutionResult | null;
  versions: VersionStatus;

  // 상태
  status: "ready" | "loading" | "saving" | "running";
  error: string | null;

  // 액션
  loadSessions: () => Promise<void>;
  selectSession: (id: string) => Promise<void>;
  selectStep: (step: string) => Promise<void>;
  updateYaml: (yaml: string) => void;
  updateVariable: (name: string, value: string) => void;
  resetVariablesToFixture: () => void;
  saveYaml: () => Promise<void>;
  runStep: (model?: string, thinkingBudget?: number) => Promise<void>;
  loadVersions: () => Promise<void>;
  clearError: () => void;
}

export const useWorkbenchStore = create<WorkbenchState>((set, get) => ({
  sessions: [],
  selectedSessionId: null,
  selectedStep: null,
  yaml: "",
  yamlDirty: false,
  fixtureInputs: {},
  editedValues: {},
  fixtureOutput: "",
  executionResult: null,
  versions: {},
  status: "ready",
  error: null,

  loadSessions: async () => {
    try {
      const sessions = await apiFetchJson<FixtureSession[]>(
        "/admin/workbench/sessions",
      );
      set({ sessions, error: null });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : "Failed to load sessions" });
    }
  },

  selectSession: async (id: string) => {
    set({
      selectedSessionId: id,
      selectedStep: null,
      yaml: "",
      yamlDirty: false,
      fixtureInputs: {},
      editedValues: {},
      fixtureOutput: "",
      executionResult: null,
      status: "loading",
      error: null,
    });

    try {
      const versions = await apiFetchJson<VersionStatus>(
        `/admin/workbench/versions?sessionId=${id}`,
      );
      set({ versions, status: "ready" });

      // 첫 번째 step 자동 선택
      const session = get().sessions.find((s) => s.sessionId === id);
      if (session?.steps.length) {
        await get().selectStep(session.steps[0]);
      }
    } catch (e) {
      set({
        status: "ready",
        error: e instanceof Error ? e.message : "Failed to load session",
      });
    }
  },

  selectStep: async (step: string) => {
    const { selectedSessionId } = get();
    if (!selectedSessionId) return;

    set({ selectedStep: step, status: "loading", editedValues: {}, executionResult: null, error: null });

    try {
      const [yamlRes, fixtureInputs, fixtureOutput] = await Promise.all([
        apiFetchJson<{ yaml: string }>(
          `/admin/workbench/yaml?step=${step}`,
        ),
        apiFetchJson<Record<string, string>>(
          `/admin/workbench/fixture-inputs?sessionId=${selectedSessionId}&step=${step}`,
        ),
        apiFetch(
          `/admin/workbench/preview?sessionId=${selectedSessionId}&step=${step}`,
        ).then((r) => r.text()).catch(() => ""),
      ]);

      set({
        yaml: yamlRes.yaml,
        yamlDirty: false,
        fixtureInputs,
        fixtureOutput,
        status: "ready",
      });
    } catch (e) {
      set({
        status: "ready",
        error: e instanceof Error ? e.message : "Failed to load step",
      });
    }
  },

  updateYaml: (yaml: string) => {
    set({ yaml, yamlDirty: true });
  },

  updateVariable: (name: string, value: string) => {
    const editedValues = { ...get().editedValues, [name]: value };
    set({ editedValues });
  },

  resetVariablesToFixture: () => {
    set({ editedValues: {} });
  },

  saveYaml: async () => {
    const { selectedStep, yaml } = get();
    if (!selectedStep) return;

    set({ status: "saving", error: null });
    try {
      await apiFetch("/admin/workbench/yaml", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ step: selectedStep, yaml }),
      });
      set({ yamlDirty: false, status: "ready" });
    } catch (e) {
      set({
        status: "ready",
        error: e instanceof Error ? e.message : "Failed to save YAML",
      });
    }
  },

  runStep: async (model?: string, thinkingBudget?: number) => {
    const { selectedSessionId, selectedStep, fixtureInputs, editedValues } =
      get();
    if (!selectedSessionId || !selectedStep) return;

    const variables = { ...fixtureInputs, ...editedValues };

    set({ status: "running", executionResult: null, error: null });
    try {
      const result = await apiFetchJson<ExecutionResult>(
        "/admin/workbench/run-step",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            sessionId: selectedSessionId,
            step: selectedStep,
            model,
            thinkingBudget,
            variables,
          }),
        },
      );
      set({ executionResult: result, status: "ready" });
    } catch (e) {
      set({
        status: "ready",
        error: e instanceof Error ? e.message : "Failed to run step",
      });
    }
  },

  loadVersions: async () => {
    const { selectedSessionId } = get();
    if (!selectedSessionId) return;
    try {
      const versions = await apiFetchJson<VersionStatus>(
        `/admin/workbench/versions?sessionId=${selectedSessionId}`,
      );
      set({ versions });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : "Failed to load versions" });
    }
  },

  clearError: () => set({ error: null }),
}));
