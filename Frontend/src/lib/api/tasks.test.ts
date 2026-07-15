import { beforeEach, describe, expect, it, vi } from "vitest";
import { mergeTaskVersion, nextTaskPollDelay, retryTask, type AiTask } from "./tasks";

function task(version: number, status: AiTask["status"] = "RUNNING"): AiTask {
  return {
    id: "task-1",
    kind: "GENERATE_FOLLOW_UP",
    status,
    stage: "CALLING_MODEL",
    progress: 40,
    attempt: 1,
    max_attempts: 3,
    retryable: true,
    version,
    result_ref: null,
    error: null,
    created_at: "2026-07-14T12:00:00Z",
    updated_at: "2026-07-14T12:00:01Z",
  };
}

describe("task version and polling", () => {
  it("忽略版本号不高于当前值的乱序事件", () => {
    const current = task(4, "RUNNING");

    expect(mergeTaskVersion(current, task(3, "QUEUED"))).toBe(current);
    expect(mergeTaskVersion(current, task(4, "FAILED"))).toBe(current);
    expect(mergeTaskVersion(current, task(5, "SUCCEEDED"))).toMatchObject({ version: 5, status: "SUCCEEDED" });
  });

  it("指数退避并限制最大轮询间隔", () => {
    expect(nextTaskPollDelay(0)).toBe(1_000);
    expect(nextTaskPollDelay(1)).toBe(2_000);
    expect(nextTaskPollDelay(5)).toBe(10_000);
    expect(nextTaskPollDelay(1, 7)).toBe(7_000);
  });
});

describe("retryTask", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () =>
        new Response(JSON.stringify({ task: task(6, "QUEUED") }), {
          status: 202,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );
  });

  it("两次明确重试操作使用不同的 Idempotency-Key", async () => {
    await retryTask(task(5, "FAILED"));
    await retryTask(task(5, "FAILED"));

    const firstHeaders = new Headers(vi.mocked(fetch).mock.calls[0]?.[1]?.headers);
    const secondHeaders = new Headers(vi.mocked(fetch).mock.calls[1]?.[1]?.headers);
    expect(firstHeaders.get("Idempotency-Key")).toMatch(/^idem-/);
    expect(secondHeaders.get("Idempotency-Key")).not.toBe(firstHeaders.get("Idempotency-Key"));
  });
});
