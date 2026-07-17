import { useEffect, useRef } from "react";

type VisiblePollingOptions = {
  enabled: boolean;
  intervalMs?: number;
  poll: (signal: AbortSignal) => void | Promise<void>;
};

/** 页面可见且功能启用时串行轮询，停用或卸载会同时取消计时器和在途请求。 */
export function useVisiblePolling({ enabled, intervalMs = 12_000, poll }: VisiblePollingOptions) {
  const pollRef = useRef(poll);
  pollRef.current = poll;

  useEffect(() => {
    if (!enabled) {
      return;
    }
    const controller = new AbortController();
    let timerId: number | null = null;
    let polling = false;

    const clearTimer = () => {
      if (timerId !== null) {
        window.clearTimeout(timerId);
        timerId = null;
      }
    };
    const schedule = () => {
      clearTimer();
      if (!controller.signal.aborted && document.visibilityState !== "hidden") {
        timerId = window.setTimeout(() => void run(), intervalMs);
      }
    };
    const run = async () => {
      if (polling || controller.signal.aborted || document.visibilityState === "hidden") {
        return;
      }
      polling = true;
      try {
        await pollRef.current(controller.signal);
      } finally {
        polling = false;
        schedule();
      }
    };
    const handleVisibilityChange = () => {
      clearTimer();
      if (document.visibilityState !== "hidden") {
        void run();
      }
    };

    document.addEventListener("visibilitychange", handleVisibilityChange);
    schedule();
    return () => {
      clearTimer();
      controller.abort();
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, [enabled, intervalMs]);
}
