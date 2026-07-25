import { beforeEach, describe, expect, it, vi } from "vitest";
import { createIdempotencyKey } from "../../lib/api";
import { createPersonalizedInterviewPackage, startPersonalizedInterviewSession, submitInterviewAnswer } from "./interviewApi";

function acceptedAnswerResponse() {
  return new Response(JSON.stringify({
    session_id: "11111111-1111-4111-8111-111111111111",
    interview_type: "job",
    status: "active",
    current_step_index: 1,
    total_steps: 4,
    current_question: null,
    report: null,
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function acceptedPackageResponse() {
  return new Response(JSON.stringify({
    package_id: "22222222-2222-4222-8222-222222222222",
    status: "ACTIVE",
    current_stage_code: "TECHNICAL_FIRST",
    expires_at: "2026-07-24T12:00:00Z",
    charged_credit: 3,
    voucher_used: false,
    stages: [],
    current_session: {
      session_id: "11111111-1111-4111-8111-111111111111",
      interview_type: "job",
      status: "active",
      current_step_index: 1,
      total_steps: 4,
      current_question: null,
      report: null,
    },
    report: null,
  }), {
    status: 201,
    headers: { "Content-Type": "application/json" },
  });
}

describe("submitInterviewAnswer", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => acceptedAnswerResponse()));
  });

  it("相邻两轮相同回答仍使用独立操作键并提交各自 turn_index", async () => {
    const firstOperationKey = createIdempotencyKey();
    const secondOperationKey = createIdempotencyKey();

    await submitInterviewAnswer("session-1", "相同回答", 0, { idempotencyKey: firstOperationKey });
    await submitInterviewAnswer("session-1", "相同回答", 1, { idempotencyKey: secondOperationKey });

    const firstRequest = vi.mocked(fetch).mock.calls[0]?.[1];
    const secondRequest = vi.mocked(fetch).mock.calls[1]?.[1];
    expect(new Headers(firstRequest?.headers).get("Idempotency-Key")).toBe(firstOperationKey);
    expect(new Headers(secondRequest?.headers).get("Idempotency-Key")).toBe(secondOperationKey);
    expect(secondOperationKey).not.toBe(firstOperationKey);
    expect(JSON.parse(String(firstRequest?.body))).toEqual({ answer_text: "相同回答", turn_index: 0 });
    expect(JSON.parse(String(secondRequest?.body))).toEqual({ answer_text: "相同回答", turn_index: 1 });
  });

  it("同一次回答发生网络重试时复用原操作键", async () => {
    const operationKey = createIdempotencyKey();
    vi.mocked(fetch)
      .mockRejectedValueOnce(new TypeError("network unavailable"))
      .mockResolvedValueOnce(acceptedAnswerResponse());

    await submitInterviewAnswer("session-1", "一次回答", 2, { idempotencyKey: operationKey });

    expect(fetch).toHaveBeenCalledTimes(2);
    const firstHeaders = new Headers(vi.mocked(fetch).mock.calls[0]?.[1]?.headers);
    const secondHeaders = new Headers(vi.mocked(fetch).mock.calls[1]?.[1]?.headers);
    expect(firstHeaders.get("Idempotency-Key")).toBe(operationKey);
    expect(secondHeaders.get("Idempotency-Key")).toBe(operationKey);
  });
});

describe("startPersonalizedInterviewSession", () => {
  it("在同一个创建请求中提交个性化资料和调用方持有的操作键", async () => {
    const operationKey = createIdempotencyKey();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(acceptedAnswerResponse()));
    const formData = new FormData();
    formData.append("session_id", "session-1");
    formData.append("interview_type", "postgraduate");
    formData.append("target_school", "清华大学");

    await startPersonalizedInterviewSession(formData, { idempotencyKey: operationKey });

    expect(vi.mocked(fetch).mock.calls[0]?.[0]).toBe("/api/interviews/personalized");
    const request = vi.mocked(fetch).mock.calls[0]?.[1];
    expect(new Headers(request?.headers).get("Idempotency-Key")).toBe(operationKey);
    expect(new Headers(request?.headers).has("Content-Type")).toBe(false);
    expect(request?.body).toBe(formData);
  });
});

describe("createPersonalizedInterviewPackage", () => {
  it("在同一个创建请求中提交工作面试套餐资料并访问 /api/interview-packages/personalized", async () => {
    const operationKey = createIdempotencyKey();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(acceptedPackageResponse()));
    const formData = new FormData();
    formData.append("package_id", "package-1");
    formData.append("first_session_id", "session-1");
    formData.append("job_title", "AI开发工程师");

    await createPersonalizedInterviewPackage(formData, { idempotencyKey: operationKey });

    expect(vi.mocked(fetch).mock.calls[0]?.[0]).toBe("/api/interview-packages/personalized");
    const request = vi.mocked(fetch).mock.calls[0]?.[1];
    expect(new Headers(request?.headers).get("Idempotency-Key")).toBe(operationKey);
    expect(new Headers(request?.headers).has("Content-Type")).toBe(false);
    expect(request?.body).toBe(formData);
  });
});
