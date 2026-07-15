export type ApiErrorPayload = {
  detail?: string;
  message?: string;
  request_id?: string;
  errors?: unknown[];
};

type ApiErrorOptions = ApiErrorPayload & {
  status: number;
  requestId: string;
  retryAfterSeconds?: number;
  cause?: unknown;
};

export class ApiError extends Error {
  readonly status: number;
  readonly detail?: string;
  readonly requestId: string;
  readonly errors: unknown[];
  readonly retryAfterSeconds?: number;

  constructor(options: ApiErrorOptions) {
    super(options.message || options.detail || "请求失败，请稍后重试。", { cause: options.cause });
    this.name = "ApiError";
    this.status = options.status;
    this.detail = options.detail;
    this.requestId = options.requestId;
    this.errors = options.errors ?? [];
    this.retryAfterSeconds = options.retryAfterSeconds;
  }
}

export function isApiErrorPayload(value: unknown): value is ApiErrorPayload {
  return Boolean(value && typeof value === "object");
}
