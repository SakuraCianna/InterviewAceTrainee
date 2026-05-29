export type CurrentUser = {
  email: string;
  role: string;
};

export type ProviderConfig = {
  id: string;
  provider_type: string;
  purpose: string;
  provider_name: string;
  model_name: string;
  priority: number;
  region: string;
  enabled: boolean;
  has_api_key: boolean;
  api_key_preview?: string | null;
};

export type AdminLoginResponse = {
  access_token?: string;
  detail?: string;
  message?: string;
  dev_code?: string;
};

export type AdminAuditLog = {
  id: string;
  admin_email: string;
  action: string;
  target_type: string;
  target_id: string;
  created_at: string;
  before_snapshot?: Record<string, unknown> | null;
  after_snapshot?: Record<string, unknown> | null;
};

export type CreditLedgerEntry = {
  id: string;
  user_email: string;
  change_amount: number;
  balance_after: number;
  reason: string;
  related_session_id?: string | null;
  operator_admin_email?: string | null;
  note?: string | null;
  created_at: string;
};

export type VoucherIssueResponse = {
  total_recipients: number;
  total_vouchers: number;
  recipients: string[];
  voucher_type: string;
  reason: string;
  operator_admin_email: string;
};

export type AICallLogEntry = {
  id: string;
  session_id?: string | null;
  provider_type: string;
  provider_name: string;
  model_name: string;
  purpose: string;
  success: boolean;
  latency_ms?: number | null;
  provider_request_id?: string | null;
  input_tokens?: number | null;
  output_tokens?: number | null;
  audio_duration_ms?: number | null;
  characters?: number | null;
  estimated_cost_cents?: number | null;
  error_message?: string | null;
  created_at: string;
};

export type ContentSafetyLogEntry = {
  id: string;
  user_email?: string | null;
  session_id?: string | null;
  source: string;
  action: string;
  risk_level: string;
  categories: string[];
  matched_terms: string[];
  content_excerpt?: string | null;
  message_code?: string | null;
  created_at: string;
};

export type AuthLoginLogEntry = {
  id: string;
  email: string;
  auth_method: string;
  role: string;
  success: boolean;
  failure_reason?: string | null;
  ip_address?: string | null;
  user_agent?: string | null;
  created_at: string;
};

export type CustomerServiceNoteEntry = {
  id: string;
  user_email: string;
  admin_email: string;
  category: string;
  content: string;
  related_session_id?: string | null;
  created_at: string;
};

export type RefundCaseEntry = {
  id: string;
  user_email: string;
  status: string;
  reason: string;
  description: string;
  amount_cents?: number | null;
  currency: string;
  credit_adjustment?: number | null;
  related_session_id?: string | null;
  resolution?: string | null;
  created_by_admin_email: string;
  updated_by_admin_email?: string | null;
  created_at: string;
  updated_at: string;
};

export type AdminUserSearchItem = {
  email: string;
  role: string;
  is_active: boolean;
  credit_balance: number;
  total_interviews: number;
  completed_interviews: number;
  last_interview_at?: string | null;
};

export type AdminInterviewHistoryItem = {
  session_id: string;
  interview_type: string;
  status: string;
  current_step_index: number;
  total_steps: number;
  report_total_score?: number | null;
  created_at: string;
};

export type AdminInterviewReport = {
  user_email: string;
  session_id: string;
  interview_type: string;
  total_score: number;
  readiness_level: string;
  score_explanation: string;
  summary: string;
  dimensions: { name: string; score: number; comment: string; level?: string | null; evidence?: string[]; action?: string | null }[];
  strengths: string[];
  improvements: string[];
  next_plan: string[];
  priority_actions: string[];
  evidence: string[];
  risk_flags: string[];
  recommended_drills: string[];
  turns: { round_name: string; question: string; answer: string; score?: number | null; feedback?: string | null; evidence?: string[] }[];
};

export type SystemConfig = {
  key: string;
  value: boolean | number | string | Record<string, unknown> | unknown[] | null;
  description: string;
  updated_at?: string | null;
};

export type AdminStatsPoint = {
  label: string;
  value: number;
};

export type AdminDashboardOverview = {
  total_users: number;
  active_users: number;
  disabled_users: number;
  admin_users: number;
  total_credit_balance: number;
  total_credit_granted: number;
  total_sessions: number;
  completed_sessions: number;
  active_sessions: number;
  today_sessions: number;
  total_reports: number;
  average_report_score?: number | null;
  ai_success_rate?: number | null;
  failed_login_count: number;
  open_refund_cases: number;
};

export type AdminTopUserUsage = {
  email: string;
  total_interviews: number;
  completed_interviews: number;
  credit_balance: number;
  last_interview_at?: string | null;
};

export type AdminDashboardStats = {
  database_ready: boolean;
  generated_at: string;
  overview: AdminDashboardOverview;
  user_growth: AdminStatsPoint[];
  daily_interviews: AdminStatsPoint[];
  daily_reports: AdminStatsPoint[];
  interview_type_distribution: AdminStatsPoint[];
  session_status_distribution: AdminStatsPoint[];
  ai_call_success_distribution: AdminStatsPoint[];
  login_outcome_distribution: AdminStatsPoint[];
  refund_status_distribution: AdminStatsPoint[];
  top_users: AdminTopUserUsage[];
};

export type AdminSectionKey = "overview" | "credits" | "users" | "ai" | "system" | "audit";
