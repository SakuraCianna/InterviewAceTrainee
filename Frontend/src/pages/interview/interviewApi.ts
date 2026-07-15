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
  }).then(({ response }) => response);
}
