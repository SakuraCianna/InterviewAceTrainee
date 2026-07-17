import { createIdempotencyKey, csrfHeaders, requestJson } from "../../lib/api";
import type {
  ApiPayload,
  CurrentUserResponse,
  InterviewAnswerResponse,
  InterviewHistoryItem,
  InterviewMaterialResponse,
  InterviewStateResponse,
  InterviewType,
  SpeechSynthesisApiResponse,
} from "./types";

const jsonHeaders = { "Content-Type": "application/json" };
const credentials = { credentials: "include" } as const;

type OperationRequestOptions = {
  idempotencyKey: string;
  signal?: AbortSignal;
};

async function retryNetworkOnce<T>(request: () => Promise<T>, signal?: AbortSignal) {
  try {
    return await request();
  } catch (error) {
    if (signal?.aborted) {
      throw error;
    }
    // 同一次业务操作可能已到达服务端，网络重试必须复用原幂等键。
    return request();
  }
}

export function getLatestInterviewMaterial(interviewType: string, signal?: AbortSignal) {
  return requestJson<InterviewMaterialResponse | null>(
    `/api/interview-materials?type=${encodeURIComponent(interviewType)}`,
    { ...credentials, signal },
    null,
  );
}

export function getCurrentAccount(signal?: AbortSignal) {
  return requestJson<CurrentUserResponse & ApiPayload>("/api/auth/me", { ...credentials, signal });
}

export function requestAccountEmailCode(email: string) {
  return requestJson<ApiPayload>("/api/auth/email-code/request", {
    method: "POST",
    credentials: "include",
    headers: jsonHeaders,
    body: JSON.stringify({ email }),
  });
}

export function changePasswordWithEmailCode(payload: { code: string; new_password: string }) {
  return requestJson<ApiPayload>("/api/auth/password/change", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify(payload),
  });
}

export function listInterviewHistory(signal?: AbortSignal) {
  return requestJson<InterviewHistoryItem[]>("/api/interviews/history", { ...credentials, signal }, []);
}

export function deleteInterviewHistoryItem(sessionId: string, signal?: AbortSignal) {
  return requestJson<void>(`/api/interviews/${encodeURIComponent(sessionId)}`, {
    method: "DELETE",
    credentials: "include",
    headers: csrfHeaders(),
    signal,
  }).then(({ response }) => response);
}

export function getActiveInterviewSession(signal?: AbortSignal) {
  return requestJson<InterviewStateResponse | null>("/api/interviews/active", { ...credentials, signal }, null);
}

export function startInterviewSession(payload: {
  session_id: string;
  interview_type: InterviewType;
  material_id?: string;
}, options: OperationRequestOptions) {
  return retryNetworkOnce(
    () => requestJson<InterviewStateResponse>("/api/interviews", {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders(jsonHeaders),
      idempotencyKey: options.idempotencyKey,
      signal: options.signal,
      body: JSON.stringify(payload),
    }),
    options.signal,
  );
}

export function uploadInterviewMaterial(formData: FormData, options: OperationRequestOptions) {
  return requestJson<InterviewMaterialResponse & ApiPayload>("/api/interview-materials", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(),
    idempotencyKey: options.idempotencyKey,
    signal: options.signal,
    body: formData,
  });
}

export function synthesizeQuestionSpeech(sessionId: string) {
  const payload = { session_id: sessionId };
  return requestJson<SpeechSynthesisApiResponse>("/api/speech/tts", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    idempotencyKey: createIdempotencyKey(),
    body: JSON.stringify(payload),
  });
}

type TtsChunk = { a: string; m: string; i: number; f: boolean };

/**
 * 流式语音合成：返回一个 AsyncGenerator，每 yield 一个 chunk 即可立即播放。
 * 调用方应在 AbortController 中止时 break 循环以释放连接。
 */
export async function* streamQuestionSpeech(
  sessionId: string,
  signal: AbortSignal,
): AsyncGenerator<TtsChunk> {
  const response = await fetch("/api/speech/tts/stream", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify({ session_id: sessionId }),
    signal,
  });
  if (!response.ok || !response.body) {
    const statusMessages: Record<number, string> = {
      401: "语音合成：登录已过期，请刷新页面重试",
      429: "语音合成：请求过于频繁，请稍后再试",
      503: "语音合成服务暂时不可用，请稍后重试",
    };
    throw new Error(statusMessages[response.status] ?? `语音合成服务异常（${response.status}）`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        if (line.startsWith("data:")) {
          const json = line.slice(5).trim();
          if (json) yield JSON.parse(json) as TtsChunk;
        }
      }
    }
  } finally {
    reader.cancel();
  }
}

export function submitInterviewAnswer(
  sessionId: string,
  answerText: string,
  turnIndex: number,
  options: OperationRequestOptions,
) {
  const payload = { answer_text: answerText, turn_index: turnIndex };
  return retryNetworkOnce(
    () => requestJson<InterviewAnswerResponse>(`/api/interviews/${encodeURIComponent(sessionId)}/answers`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders(jsonHeaders),
      idempotencyKey: options.idempotencyKey,
      signal: options.signal,
      body: JSON.stringify(payload),
    }),
    options.signal,
  );
}

export function getInterviewSession(sessionId: string, signal?: AbortSignal) {
  return requestJson<InterviewStateResponse>(
    `/api/interviews/${encodeURIComponent(sessionId)}`,
    { ...credentials, signal },
  );
}

export function logoutAccount() {
  return requestJson<void>("/api/auth/logout", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(),
  }).then(({ response }) => response);
}

export function getActiveInterviewPackage(signal?: AbortSignal) {
  return requestJson<import("./types").InterviewPackage | null>(
    "/api/interview-packages/active",
    { ...credentials, signal },
    null,
  );
}

export function createInterviewPackage(
  payload: { package_id: string; first_session_id: string; material_id: string },
  options: OperationRequestOptions,
) {
  return requestJson<import("./types").InterviewPackage>("/api/interview-packages", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    idempotencyKey: options.idempotencyKey,
    signal: options.signal,
    body: JSON.stringify(payload),
  });
}

export function startPackageNextStage(
  packageId: string,
  stageCode: string,
  sessionId: string,
  options: OperationRequestOptions,
) {
  return requestJson<import("./types").InterviewPackage>(
    `/api/interview-packages/${encodeURIComponent(packageId)}/stages/${encodeURIComponent(stageCode)}/start`,
    {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders(jsonHeaders),
      idempotencyKey: options.idempotencyKey,
      signal: options.signal,
      body: JSON.stringify({ session_id: sessionId }),
    },
  );
}
