import { useEffect, useRef } from "react";

interface UsePollingOptions {
  intervalMs?: number;
  enabled?: boolean;
}

export function usePolling(
  fn: () => Promise<void>,
  { intervalMs = 5000, enabled = true }: UsePollingOptions = {},
) {
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!enabled) return;

    fn();
    intervalRef.current = setInterval(fn, intervalMs);

    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [fn, intervalMs, enabled]);

  return {
    stop: () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
    },
  };
}
