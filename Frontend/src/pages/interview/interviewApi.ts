import { csrfHeaders } from "../../lib/api";
import type {
  ApiPayload,
  CurrentUserResponse,
  InterviewHistoryItem,
  InterviewMaterialResponse,
  InterviewStateResponse,
  InterviewType,
  SpeechSynthesisApiResponse,
} from "./types";

type ApiResult<T> = {
  response: Response;
  data: T;
};

const jsonHeaders = { "Content-Type": "application/json" };
const emptyPayload = {} as ApiPayload;
const credentials = { credentials: "include" } as const;

async function requestJson<T>(input: RequestInfo | URL, init?: RequestInit, fallback?: T): Promise<ApiResult<T>> {
  const response = await fetch(input, init);
  const data = (await response.json().catch(() => fallback ?? emptyPayload)) as T;
  return { response, data };
}

export function getCurrentAccount() {
  return requestJson<CurrentUserResponse & ApiPayload>("/api/auth/me", credentials);
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

export function listInterviewHistory() {
  return requestJson<InterviewHistoryItem[]>("/api/interviews/history", credentials, []);
}

export function deleteInterviewHistoryItem(sessionId: string) {
  return fetch(`/api/interviews/${encodeURIComponent(sessionId)}`, {
    method: "DELETE",
    credentials: "include",
    headers: csrfHeaders(),
  });
}

export function getActiveInterviewSession() {
  return requestJson<InterviewStateResponse>("/api/interviews/active", credentials);
}

export function startInterviewSession(payload: {
  session_id: string;
  interview_type: InterviewType;
  material_id?: string;
}) {
  return requestJson<InterviewStateResponse>("/api/interviews", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify(payload),
  });
}

export function uploadInterviewMaterial(formData: FormData) {
  return requestJson<InterviewMaterialResponse & ApiPayload>("/api/interview-materials", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(),
    body: formData,
  });
}

export function synthesizeQuestionSpeech(payload: {
  text: string;
  interview_type?: InterviewType;
  session_id: string;
}) {
  return requestJson<SpeechSynthesisApiResponse>("/api/speech/tts", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify(payload),
  });
}

export function submitInterviewAnswer(sessionId: string, answerText: string) {
  return requestJson<InterviewStateResponse>(`/api/interviews/${encodeURIComponent(sessionId)}/answers`, {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify({ answer_text: answerText }),
  });
}

export function getInterviewSession(sessionId: string) {
  return requestJson<InterviewStateResponse>(
    `/api/interviews/${encodeURIComponent(sessionId)}`,
    credentials,
  );
}

export function logoutAccount() {
  return fetch("/api/auth/logout", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(),
  });
}
