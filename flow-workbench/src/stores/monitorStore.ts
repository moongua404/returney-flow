import { create } from "zustand";
import { apiFetchJson } from "@/lib/api/client";
import type {
  SessionSummary,
  AdminStats,
  PipelineResponse,
  PipelineEvent,
} from "@/types";

interface MonitorState {
  sessions: SessionSummary[];
  selectedSessionId: string | null;
  stats: AdminStats | null;
  pipeline: PipelineEvent[];
  expandedEvents: Set<number>;
  exchangeRate: number;

  loadSessions: () => Promise<void>;
  loadStats: () => Promise<void>;
  selectSession: (id: string) => Promise<void>;
  toggleEvent: (index: number) => void;
  setExchangeRate: (rate: number) => void;
  refreshAll: () => Promise<void>;
}

export const useMonitorStore = create<MonitorState>((set, get) => ({
  sessions: [],
  selectedSessionId: null,
  stats: null,
  pipeline: [],
  expandedEvents: new Set(),
  exchangeRate: 1300,

  loadSessions: async () => {
    const sessions = await apiFetchJson<SessionSummary[]>(
      "/admin/sessions?limit=50",
    );
    set({ sessions });
  },

  loadStats: async () => {
    const stats = await apiFetchJson<AdminStats>("/admin/stats");
    set({ stats });
  },

  selectSession: async (id: string) => {
    set({ selectedSessionId: id, expandedEvents: new Set() });
    const data = await apiFetchJson<PipelineResponse>(
      `/admin/sessions/${id}/pipeline`,
    );
    set({ pipeline: data.events });
  },

  toggleEvent: (index: number) => {
    const expanded = new Set(get().expandedEvents);
    if (expanded.has(index)) {
      expanded.delete(index);
    } else {
      expanded.add(index);
    }
    set({ expandedEvents: expanded });
  },

  setExchangeRate: (rate: number) => set({ exchangeRate: rate }),

  refreshAll: async () => {
    const { loadSessions, loadStats, selectedSessionId, selectSession } =
      get();
    await Promise.all([loadSessions(), loadStats()]);
    if (selectedSessionId) {
      await selectSession(selectedSessionId);
    }
  },
}));
