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

export type AdminUserListResponse = {
  items: AdminUserSearchItem[];
  total: number;
  limit: number;
  offset: number;
  has_more: boolean;
  total_is_estimated: boolean;
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

export type InterviewCoreCapabilityCards = {
  ready: boolean;
  source_version?: string | null;
  source_policy?: string | null;
  total_seed_count: number;
  counts_by_interview_type: Record<string, number>;
  expected_minimums: Record<string, number>;
  missing_preset_files: string[];
  duplicate_seed_ids: string[];
  error?: string | null;
};

export type InterviewCoreCapabilityVectors = {
  ready: boolean;
  table_name: string;
  table_exists: boolean;
  expected_seed_count: number;
  total_vector_count: number;
  non_empty_vector_count: number;
  distinct_seed_count?: number | null;
  coverage_rate: number;
  seed_id_column?: string | null;
  vector_column?: string | null;
  embedding_model_column?: string | null;
  embedding_models: { model: string; count: number }[];
  status_column?: string | null;
  status_counts: { status: string; count: number }[];
  missing_observation_columns: string[];
  detail?: string | null;
};

export type InterviewCoreRecallProbe = {
  name: string;
  interview_type: string;
  expected_preset_id: string;
  matched_preset_id?: string | null;
  matched_title?: string | null;
  top_score: number;
  runner_up_score?: number | null;
  top_score_gap?: number | null;
  match_count: number;
  ready: boolean;
};

export type InterviewCoreRecallQuality = {
  ready: boolean;
  probe_count: number;
  passed_probe_count: number;
  probes: InterviewCoreRecallProbe[];
};

export type InterviewCoreHealth = {
  ready: boolean;
  capability_cards: InterviewCoreCapabilityCards;
  capability_vectors: InterviewCoreCapabilityVectors;
  recall_quality: InterviewCoreRecallQuality;
  failure_reasons: string[];
  failure_summary: string;
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

export type AdminSectionKey = "overview" | "users" | "credits" | "quality" | "ai" | "audit" | "system";
