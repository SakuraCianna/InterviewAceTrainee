import { useCallback, useEffect, useRef, useState } from "react";
import {
  getTask as getTaskRequest,
  isTerminalTask,
  mergeTaskVersion,
  nextTaskPollDelay,
  retryTask as retryTaskRequest,
  type AiTask,
} from "../../../lib/api";

type UseInterviewTaskOptions = {
  initialTask?: AiTask | null;
  fetchTask?: (taskId: string, signal?: AbortSignal) => Promise<AiTask>;
  retryTask?: (task: AiTask, signal?: AbortSignal) => Promise<AiTask>;
  pollDelay?: (attempt: number) => number;
  onSucceeded?: (task: AiTask) => void | Promise<void>;
  onFailed?: (task: AiTask) => void | Promise<void>;
};

export function useInterviewTask(options: UseInterviewTaskOptions = {}) {
  const [task, setTask] = useState<AiTask | null>(options.initialTask ?? null);
  const [error, setError] = useState<Error | null>(null);
  const [isPolling, setIsPolling] = useState(false);
  const [isRetrying, setIsRetrying] = useState(false);
  const taskRef = useRef(task);
  const retryControllerRef = useRef<AbortController | null>(null);
  const refreshControllerRef = useRef<AbortController | null>(null);
  const retryLockRef = useRef(false);
  const optionsRef = useRef(options);
  optionsRef.current = options;

  const trackTask = useCallback((incoming: AiTask) => {
    const merged = mergeTaskVersion(taskRef.current, incoming);
    if (merged === taskRef.current) {
      return merged;
    }
    taskRef.current = merged;
    setTask(merged);
    setError(null);
    return merged;
  }, []);

  const clearTask = useCallback(() => {
    taskRef.current = null;
    setTask(null);
    setError(null);
  }, []);

  const activeTaskId = task && !isTerminalTask(task) ? task.id : null;

  useEffect(() => {
    if (!activeTaskId) {
      setIsPolling(false);
      return;
    }

    const controller = new AbortController();
    let stopped = false;
    let timerId: number | null = null;
    let pollAttempt = 0;
    let requestInFlight = false;
    let repollRequested = false;

    const canPoll = () => !stopped && document.visibilityState !== "hidden" && navigator.onLine !== false;

    const clearTimer = () => {
      if (timerId !== null) {
        window.clearTimeout(timerId);
        timerId = null;
      }
    };

    const schedule = (poll: () => Promise<void>) => {
      if (!canPoll()) {
        return;
      }
      const delay = optionsRef.current.pollDelay?.(pollAttempt) ?? nextTaskPollDelay(pollAttempt);
      pollAttempt += 1;
      timerId = window.setTimeout(() => void poll(), delay);
    };

    const poll = async () => {
      if (!canPoll()) {
        return;
      }
      if (requestInFlight) {
        // 可见性和联网事件可能同时到达；保留一次补充同步，避免并发请求覆盖较新任务版本。
        repollRequested = true;
        return;
      }
      requestInFlight = true;
      setIsPolling(true);
      try {
        const fetchTask = optionsRef.current.fetchTask ?? getTaskRequest;
        const incoming = await fetchTask(activeTaskId, controller.signal);
        if (stopped) {
          return;
        }
        const previous = taskRef.current;
        const merged = trackTask(incoming);
        if (merged === previous) {
          schedule(poll);
          return;
        }
        pollAttempt = 0;
        if (merged.status === "SUCCEEDED") {
          await optionsRef.current.onSucceeded?.(merged);
          return;
        }
        if (merged.status === "FAILED" || merged.status === "CANCELLED") {
          await optionsRef.current.onFailed?.(merged);
          return;
        }
        schedule(poll);
      } catch (cause) {
        if (controller.signal.aborted || stopped) {
          return;
        }
        setError(cause instanceof Error ? cause : new Error("任务状态读取失败。"));
        schedule(poll);
      } finally {
        requestInFlight = false;
        if (!stopped) {
          setIsPolling(false);
        }
        if (repollRequested && canPoll()) {
          repollRequested = false;
          clearTimer();
          timerId = window.setTimeout(() => void poll(), 0);
        }
      }
    };

    const handleVisibilityChange = () => {
      clearTimer();
      if (document.visibilityState !== "hidden") {
        void poll();
      }
    };

    const handleOnline = () => {
      clearTimer();
      pollAttempt = 0;
      setError(null);
      void poll();
    };

    const handleOffline = () => {
      clearTimer();
      setIsPolling(false);
      setError(new Error("网络已断开，恢复联网后会自动同步任务状态。"));
    };

    document.addEventListener("visibilitychange", handleVisibilityChange);
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    if (canPoll()) {
      void poll();
    }

    return () => {
      stopped = true;
      clearTimer();
      controller.abort();
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, [activeTaskId, trackTask]);

  useEffect(() => {
    return () => {
      retryControllerRef.current?.abort();
      refreshControllerRef.current?.abort();
    };
  }, []);

  const refresh = useCallback(async () => {
    const current = taskRef.current;
    if (!current) {
      return;
    }
    refreshControllerRef.current?.abort();
    const controller = new AbortController();
    refreshControllerRef.current = controller;
    setIsPolling(true);
    setError(null);
    try {
      const fetchTask = optionsRef.current.fetchTask ?? getTaskRequest;
      const incoming = await fetchTask(current.id, controller.signal);
      const merged = trackTask(incoming);
      if (merged.status === "SUCCEEDED") {
        await optionsRef.current.onSucceeded?.(merged);
      } else if (merged.status === "FAILED" || merged.status === "CANCELLED") {
        await optionsRef.current.onFailed?.(merged);
      }
    } catch (cause) {
      if (!controller.signal.aborted) {
        setError(cause instanceof Error ? cause : new Error("任务状态读取失败。"));
      }
    } finally {
      if (refreshControllerRef.current === controller) {
        refreshControllerRef.current = null;
        setIsPolling(false);
      }
    }
  }, [trackTask]);

  const retry = useCallback(async () => {
    const current = taskRef.current;
    if (!current || current.status !== "FAILED" || !current.retryable || retryLockRef.current) {
      return;
    }
    retryLockRef.current = true;
    setIsRetrying(true);
    setError(null);
    const controller = new AbortController();
    retryControllerRef.current = controller;
    try {
      const retryRequest = optionsRef.current.retryTask ?? retryTaskRequest;
      const nextTask = await retryRequest(current, controller.signal);
      trackTask(nextTask);
    } catch (cause) {
      if (!controller.signal.aborted) {
        setError(cause instanceof Error ? cause : new Error("任务重试失败。"));
      }
    } finally {
      if (retryControllerRef.current === controller) {
        retryControllerRef.current = null;
      }
      retryLockRef.current = false;
      setIsRetrying(false);
    }
  }, [trackTask]);

  return {
    task,
    error,
    isPolling,
    isRetrying,
    isPending: Boolean(task && !isTerminalTask(task)),
    trackTask,
    clearTask,
    refresh,
    retry,
  };
}
