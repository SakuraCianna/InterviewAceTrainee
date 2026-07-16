import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./errors";
import { apiRequest, requestJson, setUnauthorizedHandler } from "./client";
import { CSRF_COOKIE_NAME } from "./csrf";

describe("apiRequest", () => {
  beforeEach(() => {
    document.cookie = `${CSRF_COOKIE_NAME}=csrf-value; path=/`;
    vi.stubGlobal("fetch", vi.fn());
    setUnauthorizedHandler(null);
  });

  it("解析 202 响应并为写请求添加安全与追踪头", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ task: { id: "task-1", status: "QUEUED" } }), {
        status: 202,
        headers: { "Content-Type": "application/json", "X-Request-Id": "req-server" },
      }),
    );

    const result = await apiRequest<{ task: { id: string; status: string } }>("/api/interviews/session-1/answers", {
      method: "POST",
      body: JSON.stringify({ answer_text: "我的回答" }),
      headers: { "Content-Type": "application/json" },
      idempotencyKey: "idem-answer-1",
    });

    expect(result.data.task).toEqual({ id: "task-1", status: "QUEUED" });
    expect(result.response.status).toBe(202);
    const requestInit = vi.mocked(fetch).mock.calls[0]?.[1];
    const headers = new Headers(requestInit?.headers);
    expect(requestInit?.credentials).toBe("include");
    expect(headers.get("X-CSRF-Token")).toBe("csrf-value");
    expect(headers.get("X-Request-Id")).toMatch(/^req-/);
    expect(headers.get("Idempotency-Key")).toBe("idem-answer-1");
  });

  it("将 204 响应解析为 undefined", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));

    const result = await apiRequest<void>("/api/auth/logout", { method: "POST" });

    expect(result.data).toBeUndefined();
  });

  it("相同写请求的两次明确调用使用不同幂等键", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));

    await requestJson<void>("/api/admin/example", { method: "POST", body: JSON.stringify({ enabled: true }) });
    await requestJson<void>("/api/admin/example", { method: "POST", body: JSON.stringify({ enabled: true }) });

    const firstHeaders = new Headers(vi.mocked(fetch).mock.calls[0]?.[1]?.headers);
    const secondHeaders = new Headers(vi.mocked(fetch).mock.calls[1]?.[1]?.headers);
    expect(firstHeaders.get("Idempotency-Key")).toMatch(/^idem-/);
    expect(secondHeaders.get("Idempotency-Key")).not.toBe(firstHeaders.get("Idempotency-Key"));
  });

  it("允许查询接口用显式 fallback 表示 204 空资源", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));

    const result = await requestJson<{ id: string } | null>("/api/interviews/active", {}, null);

    expect(result.response.status).toBe(204);
    expect(result.data).toBeNull();
  });

  it("将统一错误协议转换为 ApiError 并保留 request id", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(
        JSON.stringify({
          detail: "AI_PROVIDER_TIMEOUT",
          message: "模型响应超时",
          request_id: "req-body",
          errors: [{ field: "answer_text", message: "too long" }],
        }),
        { status: 503, headers: { "Content-Type": "application/json" } },
      ),
    );

    await expect(apiRequest("/api/tasks/task-1")).rejects.toMatchObject({
      name: "ApiError",
      status: 503,
      detail: "AI_PROVIDER_TIMEOUT",
      message: "模型响应超时",
      requestId: "req-body",
    });
  });

  it("在 401 时调用统一未授权处理器", async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ detail: "invalid_access_token", request_id: "req-401" }), {
        status: 401,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(apiRequest("/api/tasks/task-1")).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledWith(expect.objectContaining({ status: 401, requestId: "req-401" }));
  });
});
