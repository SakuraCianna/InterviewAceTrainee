import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { AiTask } from "../../../lib/api";
import { TaskProgress } from "./TaskProgress";

const failedTask: AiTask = {
  id: "task-1",
  kind: "GENERATE_REPORT",
  status: "FAILED",
  stage: "CALLING_MODEL",
  progress: 65,
  attempt: 3,
  max_attempts: 3,
  retryable: true,
  version: 5,
  result_ref: null,
  error: { code: "AI_PROVIDER_TIMEOUT", message: "模型响应超时" },
  created_at: "2026-07-14T12:00:00Z",
  updated_at: "2026-07-14T12:00:01Z",
};

describe("TaskProgress", () => {
  it("以 aria-live 和 progress 语义呈现失败，并锁住重复重试", async () => {
    let resolveRetry: () => void = () => undefined;
    const onRetry = vi.fn(() => new Promise<void>((resolve) => {
      resolveRetry = resolve;
    }));
    render(<TaskProgress task={failedTask} onRetry={onRetry} />);

    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "65");
    expect(screen.getByText("模型响应超时")).toBeInTheDocument();

    const retryButton = screen.getByRole("button", { name: "重试任务" });
    await userEvent.dblClick(retryButton);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(retryButton).toBeDisabled();

    resolveRetry();
  });

  it("同步失败时展示友好提示并允许立即刷新", async () => {
    const onRefresh = vi.fn().mockResolvedValue(undefined);
    render(
      <TaskProgress
        task={{ ...failedTask, status: "RUNNING", stage: "LEASE_RECOVERY", retryable: false, error: null }}
        syncError="网络暂时不可用"
        onRefresh={onRefresh}
      />,
    );

    expect(screen.getByText("处理节点中断，正在安全恢复任务")).toBeInTheDocument();
    expect(screen.getByText(/状态同步暂时失败/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "立即刷新状态" }));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });
});
