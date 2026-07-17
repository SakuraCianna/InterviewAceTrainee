import { apiRequest } from "./client";
import { ApiError } from "./errors";
import { createIdempotencyKey } from "./idempotency";

export type TaskStatus = "QUEUED" | "RUNNING" | "RETRYING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export type TaskFailure = {
  code: string;
  message?: string;
};

export type AiTask = {
  id: string;
  kind: "ANALYZE_MATERIAL" | "GENERATE_FOLLOW_UP" | "GENERATE_REPORT" | "SYNTHESIZE_SPEECH" | string;
  status: TaskStatus;
  stage: string;
  progress: number;
  attempt: number;
  max_attempts: number;
  retryable: boolean;
  version: number;
  result_ref: Record<string, unknown> | string | null;
  error: TaskFailure | null;
  created_at: string;
  updated_at: string;
  next_attempt_at?: string | null;
  session_id?: string | null;
};

export type TaskEnvelope<TSession = unknown> = {
  request_id?: string;
  session?: TSession;
  task: AiTask;
};

export type AdminTaskPage = {
  items: AiTask[];
  total: number;
  limit: number;
  offset: number;
  has_more: boolean;
};

export function isTerminalTask(task: AiTask | null): boolean {
  return Boolean(task && (task.status === "SUCCEEDED" || task.status === "FAILED" || task.status === "CANCELLED"));
}

export function mergeTaskVersion(current: AiTask | null, incoming: AiTask) {
  if (current && incoming.id === current.id && incoming.version <= current.version) {
    return current;
  }
  return incoming;
}

export function nextTaskPollDelay(attempt: number, retryAfter?: number) {
  if (retryAfter != null && Number.isFinite(retryAfter)) {
    return Math.min(10_000, Math.max(1_000, retryAfter * 1_000));
  }
  return Math.min(10_000, 1_000 * 2 ** Math.max(0, attempt));
}

function unwrapTask(payload: AiTask | TaskEnvelope) {
  return "task" in payload ? payload.task : payload;
}

export async function getTask(taskId: string, signal?: AbortSignal) {
  const result = await apiRequest<AiTask | TaskEnvelope>(`/api/tasks/${encodeURIComponent(taskId)}`, {
    signal,
    timeoutMs: 12_000,
  });
  return unwrapTask(result.data);
}

export async function retryTask(task: AiTask, signal?: AbortSignal, operationKey = createIdempotencyKey()) {
  if (task.status !== "FAILED" || !task.retryable) {
    throw new ApiError({
      status: 409,
      detail: "task_not_retryable",
      message: "当前任务不可重试。",
      requestId: "client-validation",
    });
  }
  const result = await apiRequest<AiTask | TaskEnvelope>(`/api/tasks/${encodeURIComponent(task.id)}/retry`, {
    method: "POST",
    idempotencyKey: operationKey,
    signal,
  });
  return unwrapTask(result.data);
}

export async function listAdminTasks(options: {
  status?: TaskStatus | "";
  kind?: string;
  sessionId?: string;
  limit?: number;
  offset?: number;
  signal?: AbortSignal;
} = {}) {
  const params = new URLSearchParams({
    limit: String(options.limit ?? 25),
    offset: String(options.offset ?? 0),
  });
  if (options.status) {
    params.set("status", options.status);
  }
  if (options.kind) {
    params.set("kind", options.kind);
  }
  if (options.sessionId) {
    params.set("session_id", options.sessionId);
  }
  const result = await apiRequest<AdminTaskPage>(`/api/admin/tasks?${params.toString()}`, {
    signal: options.signal,
  });
  return result.data;
}
