import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AiTask } from "../../../lib/api";
import { useInterviewTask } from "./useInterviewTask";

function task(version: number, status: AiTask["status"]): AiTask {
  return {
    id: "task-1",
    kind: "GENERATE_FOLLOW_UP",
    status,
    stage: status === "FAILED" ? "CALLING_MODEL" : "WAITING_FOR_WORKER",
    progress: status === "SUCCEEDED" ? 100 : 20,
    attempt: 1,
    max_attempts: 3,
    retryable: status === "FAILED",
    version,
    result_ref: null,
    error: status === "FAILED" ? { code: "AI_PROVIDER_TIMEOUT", message: "模型响应超时" } : null,
    created_at: "2026-07-14T12:00:00Z",
    updated_at: "2026-07-14T12:00:01Z",
  };
}

type HarnessProps = {
  initialTask: AiTask;
  fetchTask: (taskId: string, signal?: AbortSignal) => Promise<AiTask>;
  retryTask?: (task: AiTask, signal?: AbortSignal) => Promise<AiTask>;
  onSucceeded?: (task: AiTask) => void;
};

function Harness({ initialTask, fetchTask, retryTask, onSucceeded }: HarnessProps) {
  const interviewTask = useInterviewTask({
    initialTask,
    fetchTask,
    retryTask,
    onSucceeded,
    pollDelay: () => 1,
  });
  return (
    <div>
      <span data-testid="version">{interviewTask.task?.version}</span>
      <span data-testid="status">{interviewTask.task?.status}</span>
      <button type="button" onClick={() => void interviewTask.retry()} disabled={interviewTask.isRetrying}>重试</button>
      <button type="button" onClick={() => void interviewTask.refresh()}>刷新</button>
    </div>
  );
}

afterEach(() => {
  Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
  Object.defineProperty(navigator, "onLine", { configurable: true, value: true });
});

describe("useInterviewTask", () => {
  it("轮询到更高版本的成功状态后停止并通知页面刷新", async () => {
    const fetchTask = vi.fn().mockResolvedValue(task(2, "SUCCEEDED"));
    const onSucceeded = vi.fn();
    render(<Harness initialTask={task(1, "QUEUED")} fetchTask={fetchTask} onSucceeded={onSucceeded} />);

    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("SUCCEEDED"));
    expect(screen.getByTestId("version")).toHaveTextContent("2");
    expect(onSucceeded).toHaveBeenCalledWith(expect.objectContaining({ version: 2 }));
    expect(fetchTask).toHaveBeenCalledTimes(1);
  });

  it("页面隐藏时暂停，恢复可见后继续，卸载时取消请求", async () => {
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
    let capturedSignal: AbortSignal | undefined;
    const fetchTask = vi.fn((_taskId: string, signal?: AbortSignal) => {
      capturedSignal = signal;
      return new Promise<AiTask>(() => undefined);
    });
    const view = render(<Harness initialTask={task(1, "RUNNING")} fetchTask={fetchTask} />);

    await act(async () => Promise.resolve());
    expect(fetchTask).not.toHaveBeenCalled();

    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
    document.dispatchEvent(new Event("visibilitychange"));
    await waitFor(() => expect(fetchTask).toHaveBeenCalledTimes(1));

    view.unmount();
    expect(capturedSignal?.aborted).toBe(true);
  });

  it("离线时暂停轮询并在网络恢复后立即同步", async () => {
    Object.defineProperty(navigator, "onLine", { configurable: true, value: false });
    const fetchTask = vi.fn().mockResolvedValue(task(2, "SUCCEEDED"));
    const onSucceeded = vi.fn();
    render(<Harness initialTask={task(1, "RUNNING")} fetchTask={fetchTask} onSucceeded={onSucceeded} />);

    await act(async () => Promise.resolve());
    expect(fetchTask).not.toHaveBeenCalled();

    Object.defineProperty(navigator, "onLine", { configurable: true, value: true });
    window.dispatchEvent(new Event("online"));

    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("SUCCEEDED"));
    expect(fetchTask).toHaveBeenCalledTimes(1);
    expect(onSucceeded).toHaveBeenCalledTimes(1);
  });

  it("失败任务只触发一次重试并接纳新的排队版本", async () => {
    const fetchTask = vi.fn().mockResolvedValue(task(4, "QUEUED"));
    const retryTask = vi.fn().mockResolvedValue(task(4, "QUEUED"));
    render(<Harness initialTask={task(3, "FAILED")} fetchTask={fetchTask} retryTask={retryTask} />);

    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("QUEUED"));
    expect(retryTask).toHaveBeenCalledTimes(1);
  });

  it("手动刷新会接纳更高版本任务状态", async () => {
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
    const fetchTask = vi.fn().mockResolvedValue(task(3, "SUCCEEDED"));
    const onSucceeded = vi.fn();
    render(<Harness initialTask={task(2, "RUNNING")} fetchTask={fetchTask} onSucceeded={onSucceeded} />);

    await userEvent.click(screen.getByRole("button", { name: "刷新" }));

    await waitFor(() => expect(screen.getByTestId("status")).toHaveTextContent("SUCCEEDED"));
    expect(screen.getByTestId("version")).toHaveTextContent("3");
    expect(onSucceeded).toHaveBeenCalledTimes(1);
  });
});
