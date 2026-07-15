import { beforeEach, describe, expect, it, vi } from "vitest";
import { createIdempotencyKey } from "../../lib/api";
import { submitInterviewAnswer, uploadInterviewMaterial } from "./interviewApi";

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

describe("uploadInterviewMaterial", () => {
  it("使用调用方持有的素材操作键", async () => {
    const operationKey = createIdempotencyKey();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      id: "material-1",
      interview_type: "job",
      extracted_text_chars: 128,
      profile_summary: "候选人摘要",
      keywords: [],
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));

    await uploadInterviewMaterial(new FormData(), { idempotencyKey: operationKey });

    const request = vi.mocked(fetch).mock.calls[0]?.[1];
    expect(new Headers(request?.headers).get("Idempotency-Key")).toBe(operationKey);
  });
});
