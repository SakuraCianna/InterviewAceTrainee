import { CSRF_COOKIE_NAME, CSRF_HEADER_NAME, getCookie } from "./csrf";
import { ApiError, isApiErrorPayload, type ApiErrorPayload } from "./errors";
import { createIdempotencyKey } from "./idempotency";

const DEFAULT_TIMEOUT_MS = 20_000;
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export type ApiRequestOptions = RequestInit & {
  timeoutMs?: number;
  csrf?: boolean;
  idempotencyKey?: string;
  requestId?: string;
  handleUnauthorized?: boolean;
};

export type ApiResult<T> = {
  response: Response;
  data: T;
  requestId: string;
  error?: ApiError;
};

type UnauthorizedHandler = ((error: ApiError) => void) | null;
let unauthorizedHandler: UnauthorizedHandler = null;

function createRequestId() {
  const suffix = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  return `req-${suffix}`;
}

function retryAfterSeconds(response: Response) {
  const value = Number.parseInt(response.headers.get("Retry-After") ?? "", 10);
  return Number.isFinite(value) && value >= 0 ? value : undefined;
}

async function parseResponse<T>(response: Response, fallback?: T): Promise<T> {
  if (response.status === 204) {
    return fallback as T;
  }
  const text = await response.text();
  if (!text) {
    return fallback as T;
  }
  const contentType = response.headers.get("Content-Type") ?? "";
  if (!contentType.includes("json")) {
    return text as T;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    return fallback as T;
  }
}

function errorFromResponse(response: Response, payload: unknown, requestId: string) {
  const errorPayload: ApiErrorPayload = isApiErrorPayload(payload) ? payload : {};
  return new ApiError({
    status: response.status,
    detail: errorPayload.detail,
    message: errorPayload.message,
    requestId: errorPayload.request_id || response.headers.get("X-Request-Id") || requestId,
    errors: errorPayload.errors,
    retryAfterSeconds: retryAfterSeconds(response),
  });
}

export function setUnauthorizedHandler(handler: UnauthorizedHandler) {
  unauthorizedHandler = handler;
}

export async function requestJson<T>(
  input: RequestInfo | URL,
  options: ApiRequestOptions = {},
  fallback?: T,
): Promise<ApiResult<T>> {
  const {
    timeoutMs = DEFAULT_TIMEOUT_MS,
    csrf = true,
    idempotencyKey,
    requestId = createRequestId(),
    handleUnauthorized = true,
    signal,
    ...requestInit
  } = options;
  const method = (requestInit.method ?? "GET").toUpperCase();
  const headers = new Headers(requestInit.headers);
  headers.set("X-Request-Id", requestId);
  const reusableKey = idempotencyKey ?? (!SAFE_METHODS.has(method) ? createIdempotencyKey() : undefined);
  if (reusableKey) {
    headers.set("Idempotency-Key", reusableKey);
  }
  if (csrf && !SAFE_METHODS.has(method)) {
    const csrfToken = getCookie(CSRF_COOKIE_NAME);
    if (csrfToken) {
      headers.set(CSRF_HEADER_NAME, csrfToken);
    }
  }

  const controller = new AbortController();
  const abortFromCaller = () => controller.abort(signal?.reason);
  if (signal?.aborted) {
    abortFromCaller();
  } else {
    signal?.addEventListener("abort", abortFromCaller, { once: true });
  }
  const timeoutId = window.setTimeout(() => controller.abort(new DOMException("Request timed out", "TimeoutError")), timeoutMs);

  let response: Response;
  try {
    response = await fetch(input, {
      ...requestInit,
      method,
      credentials: requestInit.credentials ?? "include",
      headers,
      signal: controller.signal,
    });
  } catch (cause) {
    if (controller.signal.aborted && !signal?.aborted) {
      throw new ApiError({
        status: 0,
        detail: "request_timeout",
        message: "请求超时，请检查网络后重试。",
        requestId,
        cause,
      });
    }
    throw cause;
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortFromCaller);
  }

  const data = await parseResponse<T>(response, fallback);
  const responseRequestId = response.headers.get("X-Request-Id") || (isApiErrorPayload(data) ? data.request_id : undefined) || requestId;
  if (response.ok) {
    return { response, data, requestId: responseRequestId };
  }

  const error = errorFromResponse(response, data, responseRequestId);
  if (response.status === 401 && handleUnauthorized) {
    unauthorizedHandler?.(error);
    window.dispatchEvent(new CustomEvent("mianba:unauthorized", { detail: error }));
  }
  return { response, data, requestId: responseRequestId, error };
}

export async function apiRequest<T>(input: RequestInfo | URL, options: ApiRequestOptions = {}): Promise<ApiResult<T>> {
  const result = await requestJson<T>(input, options);
  if (result.error) {
    throw result.error;
  }
  return result;
}
