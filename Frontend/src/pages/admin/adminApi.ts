import { csrfHeaders } from "../../lib/api";
import type {
  AICallLogEntry,
  AdminAuditLog,
  AdminDashboardStats,
  AdminInterviewHistoryItem,
  AdminInterviewReport,
  AdminLoginResponse,
  AdminUserListResponse,
  AdminUserSearchItem,
  AuthLoginLogEntry,
  ContentSafetyLogEntry,
  CreditLedgerEntry,
  CurrentUser,
  CustomerServiceNoteEntry,
  InterviewCoreHealth,
  ProviderConfig,
  RefundCaseEntry,
  SystemConfig,
  VoucherIssueResponse,
} from "./types";

type ApiResult<T> = {
  response: Response;
  data: T;
};

export type AdminApiErrorPayload = {
  detail?: string;
  message?: string;
};

export type CreditAdjustmentResponse = AdminApiErrorPayload & {
  balance_after?: number;
};

export type ProviderTestResponse = AdminApiErrorPayload & {
  success?: boolean;
};

export type UserRoleResponse = AdminApiErrorPayload & {
  role?: string;
};

const jsonHeaders = { "Content-Type": "application/json" };
const emptyObject = {} as AdminApiErrorPayload;

async function requestJson<T>(input: RequestInfo | URL, init?: RequestInit, fallback?: T): Promise<ApiResult<T>> {
  const response = await fetch(input, init);
  const data = (await response.json().catch(() => fallback ?? emptyObject)) as T;
  return { response, data };
}

const credentials = { credentials: "include" } as const;

export function getCurrentUser() {
  return requestJson<CurrentUser & AdminApiErrorPayload>("/api/auth/me", credentials);
}

export function listProviders() {
  return requestJson<ProviderConfig[]>("/api/ai-providers", credentials, []);
}

export function listAuditLogs() {
  return requestJson<AdminAuditLog[]>("/api/admin/audit-logs", credentials, []);
}

export function listAiCallLogs() {
  return requestJson<AICallLogEntry[]>("/api/admin/ai-call-logs", credentials, []);
}

export function listContentSafetyLogs() {
  return requestJson<ContentSafetyLogEntry[]>("/api/admin/content-safety-logs", credentials, []);
}

export function listAuthLoginLogs(userEmail?: string) {
  const normalizedEmail = userEmail?.trim();
  const url = normalizedEmail
    ? `/api/admin/users/${encodeURIComponent(normalizedEmail)}/auth-login-logs`
    : "/api/admin/auth-login-logs";
  return requestJson<AuthLoginLogEntry[]>(url, credentials, []);
}

export function listCustomerServiceNotes(userEmail: string) {
  return requestJson<CustomerServiceNoteEntry[]>(
    `/api/admin/users/${encodeURIComponent(userEmail)}/notes`,
    credentials,
    [],
  );
}

export function listRefundCases(userEmail?: string) {
  const normalizedEmail = userEmail?.trim();
  const url = normalizedEmail
    ? `/api/admin/users/${encodeURIComponent(normalizedEmail)}/refund-cases`
    : "/api/admin/refund-cases";
  return requestJson<RefundCaseEntry[]>(url, credentials, []);
}

export function listSystemConfigs() {
  return requestJson<SystemConfig[]>("/api/admin/system-configs", credentials, []);
}

export function getDashboardStats() {
  return requestJson<AdminDashboardStats>("/api/admin/stats", credentials);
}

export function listAdminUsers(query = "", limit = 50, offset = 0) {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  const normalizedQuery = query.trim();
  if (normalizedQuery) {
    params.set("query", normalizedQuery);
  }
  return requestJson<AdminUserListResponse>(
    `/api/admin/users?${params.toString()}`,
    credentials,
    { items: [], total: 0, limit, offset, has_more: false, total_is_estimated: false },
  );
}

export function getInterviewCoreHealth() {
  return requestJson<InterviewCoreHealth & AdminApiErrorPayload>("/api/health/interview-core", credentials);
}

export function listCreditLedger(userEmail: string) {
  return requestJson<CreditLedgerEntry[]>(
    `/api/admin/users/${encodeURIComponent(userEmail)}/credit-ledger`,
    credentials,
    [],
  );
}

export function searchAdminUsers(query: string) {
  return requestJson<AdminUserSearchItem[]>(
    `/api/admin/users/search?query=${encodeURIComponent(query)}`,
    credentials,
    [],
  );
}

export function listUserInterviews(userEmail: string) {
  return requestJson<AdminInterviewHistoryItem[]>(
    `/api/admin/users/${encodeURIComponent(userEmail)}/interviews`,
    credentials,
    [],
  );
}

export function getUserInterviewReport(userEmail: string, sessionId: string) {
  return requestJson<AdminInterviewReport>(
    `/api/admin/users/${encodeURIComponent(userEmail)}/interviews/${encodeURIComponent(sessionId)}/report`,
    credentials,
  );
}

export function requestAdminEmailCode(email: string) {
  return requestJson<AdminLoginResponse>("/api/auth/email-code/request", {
    method: "POST",
    credentials: "include",
    headers: jsonHeaders,
    body: JSON.stringify({ email }),
  });
}

export function loginAdmin(email: string, password: string, code: string) {
  return requestJson<AdminLoginResponse>("/api/auth/admin/login", {
    method: "POST",
    credentials: "include",
    headers: jsonHeaders,
    body: JSON.stringify({ email, password, code }),
  });
}

export function adjustUserCredits(
  userEmail: string,
  payload: { change_amount: number; reason: string; note?: string },
) {
  return requestJson<CreditAdjustmentResponse>(`/api/admin/users/${encodeURIComponent(userEmail)}/credits`, {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify(payload),
  });
}

export function issueVouchers(payload: {
  user_emails: string[];
  issue_all_active_users: boolean;
  quantity: number;
  voucher_type: string;
  reason: string;
  note?: string;
}) {
  return requestJson<VoucherIssueResponse & AdminApiErrorPayload>("/api/admin/vouchers", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify(payload),
  });
}

export function createCustomerServiceNote(
  userEmail: string,
  payload: { category: string; content: string; related_session_id?: string },
) {
  return requestJson<AdminApiErrorPayload>(`/api/admin/users/${encodeURIComponent(userEmail)}/notes`, {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify(payload),
  });
}

export function createRefundCase(
  userEmail: string,
  payload: {
    reason: string;
    description: string;
    amount_cents?: number;
    currency: string;
    credit_adjustment?: number;
    related_session_id?: string;
  },
) {
  return requestJson<AdminApiErrorPayload>(`/api/admin/users/${encodeURIComponent(userEmail)}/refund-cases`, {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify(payload),
  });
}

export function updateRefundCase(
  caseId: string,
  payload: { status: string; resolution?: string | null },
) {
  return requestJson<AdminApiErrorPayload>(`/api/admin/refund-cases/${encodeURIComponent(caseId)}`, {
    method: "PUT",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify(payload),
  });
}

export function updateProviderEnabled(providerId: string, enabled: boolean) {
  return requestJson<AdminApiErrorPayload>(`/api/ai-providers/${encodeURIComponent(providerId)}`, {
    method: "PUT",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify({ enabled }),
  });
}

export function testProviderConfig(providerId: string) {
  return requestJson<ProviderTestResponse>(`/api/ai-providers/${encodeURIComponent(providerId)}/test`, {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(),
  });
}

export function updateAdminUserStatus(userEmail: string, isActive: boolean) {
  return requestJson<AdminApiErrorPayload>(`/api/admin/users/${encodeURIComponent(userEmail)}/status`, {
    method: "PUT",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify({ is_active: isActive, reason: isActive ? "manual_restore" : "manual_disable" }),
  });
}

export function updateAdminUserRole(userEmail: string, role: "user" | "admin") {
  return requestJson<UserRoleResponse>(`/api/admin/users/${encodeURIComponent(userEmail)}/role`, {
    method: "PUT",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify({ role, reason: role === "admin" ? "grant_admin" : "revoke_admin" }),
  });
}

export function updateSystemConfigValue(configKey: string, value: SystemConfig["value"]) {
  return requestJson<SystemConfig & AdminApiErrorPayload>(`/api/admin/system-configs/${encodeURIComponent(configKey)}`, {
    method: "PUT",
    credentials: "include",
    headers: csrfHeaders(jsonHeaders),
    body: JSON.stringify({ value }),
  });
}

export function logoutAdmin() {
  return fetch("/api/auth/logout", {
    method: "POST",
    credentials: "include",
    headers: csrfHeaders(),
  });
}
