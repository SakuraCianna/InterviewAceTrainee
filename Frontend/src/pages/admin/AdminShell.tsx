import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import type { EChartsOption } from "echarts";
import { AppIcon } from "../../components/AppIcon";
import { BrandLogo } from "../../components/BrandLogo";
import { CSRF_COOKIE_NAME, csrfHeaders, getApiErrorMessage, getCookie } from "../../lib/api";

type EChartsModule = typeof import("echarts");
type EChartsInstance = ReturnType<EChartsModule["init"]>;

let echartsLoader: Promise<EChartsModule> | null = null;

function loadECharts() {
  echartsLoader ??= import("echarts");
  return echartsLoader;
}

const ADMIN_CODE_COOLDOWN_SECONDS = 90;
const ADMIN_CODE_STORAGE_PREFIX = "mianba_admin_code_next:";

function normalizeAdminEmail(email: string) {
  return email.trim().toLowerCase();
}

function adminCodeCooldownKey(email: string) {
  return `${ADMIN_CODE_STORAGE_PREFIX}${normalizeAdminEmail(email)}`;
}

function secondsUntil(timestamp: number) {
  return Math.max(0, Math.ceil((timestamp - Date.now()) / 1000));
}

function retryAfterSeconds(response: Response) {
  const rawValue = response.headers.get("Retry-After");
  const parsedValue = rawValue ? Number.parseInt(rawValue, 10) : ADMIN_CODE_COOLDOWN_SECONDS;
  return Number.isFinite(parsedValue) && parsedValue > 0 ? parsedValue : ADMIN_CODE_COOLDOWN_SECONDS;
}

type CurrentUser = {
  email: string;
  role: string;
};

type ProviderConfig = {
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

type AdminLoginResponse = {
  access_token?: string;
  detail?: string;
  message?: string;
  dev_code?: string;
};

type AdminAuditLog = {
  id: string;
  admin_email: string;
  action: string;
  target_type: string;
  target_id: string;
  created_at: string;
  before_snapshot?: Record<string, unknown> | null;
  after_snapshot?: Record<string, unknown> | null;
};

type CreditLedgerEntry = {
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

type AICallLogEntry = {
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

type ContentSafetyLogEntry = {
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

type AuthLoginLogEntry = {
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

type CustomerServiceNoteEntry = {
  id: string;
  user_email: string;
  admin_email: string;
  category: string;
  content: string;
  related_session_id?: string | null;
  created_at: string;
};

type RefundCaseEntry = {
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

type AdminUserSearchItem = {
  email: string;
  role: string;
  is_active: boolean;
  credit_balance: number;
  total_interviews: number;
  completed_interviews: number;
  last_interview_at?: string | null;
};

type AdminInterviewHistoryItem = {
  session_id: string;
  interview_type: string;
  status: string;
  current_step_index: number;
  total_steps: number;
  report_total_score?: number | null;
  created_at: string;
};

type AdminInterviewReport = {
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

type SystemConfig = {
  key: string;
  value: boolean | number | string | Record<string, unknown> | unknown[] | null;
  description: string;
  updated_at?: string | null;
};

type AdminStatsPoint = {
  label: string;
  value: number;
};

type AdminDashboardOverview = {
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

type AdminTopUserUsage = {
  email: string;
  total_interviews: number;
  completed_interviews: number;
  credit_balance: number;
  last_interview_at?: string | null;
};

type AdminDashboardStats = {
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

type AdminSectionKey = "overview" | "credits" | "users" | "ai" | "system" | "audit";

const adminSectionNavItems: { key: AdminSectionKey; label: string; icon: string }[] = [
  { key: "overview", label: "概览", icon: "lucide:gauge" },
  { key: "credits", label: "次数", icon: "lucide:coins" },
  { key: "users", label: "用户", icon: "lucide:users" },
  { key: "ai", label: "AI 服务", icon: "lucide:bot" },
  { key: "system", label: "系统", icon: "lucide:settings-2" },
  { key: "audit", label: "审计", icon: "lucide:shield-check" },
];

const creditReasonOptions = [
  { value: "manual_grant", label: "人工开通", help: "用户购买或人工确认后增加次数。" },
  { value: "trial_bonus", label: "试用赠送", help: "新用户、活动或人工试用赠送。" },
  { value: "service_compensation", label: "服务补偿", help: "验证码、模型、语音等系统问题导致体验受损后补偿。" },
  { value: "refund_adjustment", label: "退款退回", help: "售后退款、纠纷处理或次数退回。" },
  { value: "manual_correction", label: "余额修正", help: "管理员核对账本后修正异常余额。" },
  { value: "manual_deduction", label: "人工扣减", help: "误发、滥用或人工确认后扣减次数。" },
];

function adminSectionFromHash(hash: string): AdminSectionKey | null {
  const key = hash.replace(/^#admin-/, "") as AdminSectionKey;
  return adminSectionNavItems.some((item) => item.key === key) ? key : null;
}

const chartTextColor = "#334155";

function lineDashboardOption(stats: AdminDashboardStats): EChartsOption {
  const labels = stats.daily_interviews.map((item) => item.label);
  return {
    color: ["#174ea6", "#b3261e", "#0b8043"],
    grid: { top: 36, right: 18, bottom: 28, left: 38 },
    legend: { top: 0, textStyle: { color: chartTextColor, fontWeight: 700 } },
    tooltip: { trigger: "axis" },
    xAxis: { type: "category", boundaryGap: false, data: labels, axisLabel: { color: chartTextColor } },
    yAxis: { type: "value", minInterval: 1, axisLabel: { color: chartTextColor }, splitLine: { lineStyle: { color: "rgba(15,23,42,0.08)" } } },
    series: [
      { name: "训练场次", type: "line", smooth: true, symbolSize: 8, lineStyle: { width: 3 }, areaStyle: { opacity: 0.1 }, data: stats.daily_interviews.map((item) => item.value) },
      { name: "生成报告", type: "line", smooth: true, symbolSize: 8, lineStyle: { width: 3 }, areaStyle: { opacity: 0.08 }, data: stats.daily_reports.map((item) => item.value) },
      { name: "新增用户", type: "line", smooth: true, symbolSize: 8, lineStyle: { width: 3 }, areaStyle: { opacity: 0.08 }, data: stats.user_growth.map((item) => item.value) },
    ],
  };
}

function donutDashboardOption(points: AdminStatsPoint[], colors: string[]): EChartsOption {
  return {
    color: colors,
    tooltip: { trigger: "item" },
    legend: { bottom: 0, textStyle: { color: chartTextColor, fontWeight: 700 } },
    series: [
      {
        type: "pie",
        radius: ["48%", "72%"],
        center: ["50%", "42%"],
        avoidLabelOverlap: true,
        label: { formatter: "{b}\n{c}", color: "#0f172a", fontWeight: 900 },
        data: points.map((item) => ({ name: item.label, value: item.value })),
      },
    ],
  };
}

function barDashboardOption(points: AdminStatsPoint[], name: string, color: string): EChartsOption {
  return {
    color: [color],
    grid: { top: 16, right: 18, bottom: 28, left: 38 },
    tooltip: { trigger: "axis" },
    xAxis: { type: "category", data: points.map((item) => item.label), axisLabel: { color: chartTextColor } },
    yAxis: { type: "value", minInterval: 1, axisLabel: { color: chartTextColor }, splitLine: { lineStyle: { color: "rgba(15,23,42,0.08)" } } },
    series: [
      {
        name,
        type: "bar",
        barMaxWidth: 42,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: points.map((item) => item.value),
      },
    ],
  };
}

function AdminChart({ option, height = 280 }: { option: EChartsOption; height?: number }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<EChartsInstance | null>(null);
  const optionRef = useRef(option);

  useEffect(() => {
    optionRef.current = option;
    chartRef.current?.setOption(option, true);
  }, [option]);

  useEffect(() => {
    let disposed = false;
    let resizeObserver: ResizeObserver | null = null;

    void loadECharts()
      .then((echarts) => {
        if (disposed || !containerRef.current) {
          return;
        }
        chartRef.current = echarts.init(containerRef.current);
        chartRef.current.setOption(optionRef.current);
        if ("ResizeObserver" in window) {
          resizeObserver = new ResizeObserver(() => chartRef.current?.resize());
          resizeObserver.observe(containerRef.current);
        }
      })
      .catch(() => undefined);

    return () => {
      disposed = true;
      resizeObserver?.disconnect();
      chartRef.current?.dispose();
      chartRef.current = null;
    };
  }, []);

  return <div className="admin-chart" ref={containerRef} style={{ minHeight: height }} />;
}

export function AdminShell() {
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [providers, setProviders] = useState<ProviderConfig[]>([]);
  const [auditLogs, setAuditLogs] = useState<AdminAuditLog[]>([]);
  const [creditLedger, setCreditLedger] = useState<CreditLedgerEntry[]>([]);
  const [aiCallLogs, setAiCallLogs] = useState<AICallLogEntry[]>([]);
  const [contentSafetyLogs, setContentSafetyLogs] = useState<ContentSafetyLogEntry[]>([]);
  const [authLoginLogs, setAuthLoginLogs] = useState<AuthLoginLogEntry[]>([]);
  const [customerServiceNotes, setCustomerServiceNotes] = useState<CustomerServiceNoteEntry[]>([]);
  const [refundCases, setRefundCases] = useState<RefundCaseEntry[]>([]);
  const [systemConfigs, setSystemConfigs] = useState<SystemConfig[]>([]);
  const [dashboardStats, setDashboardStats] = useState<AdminDashboardStats | null>(null);
  const [providerTestResults, setProviderTestResults] = useState<Record<string, string>>({});
  const [userSearchQuery, setUserSearchQuery] = useState("");
  const [userSearchResults, setUserSearchResults] = useState<AdminUserSearchItem[]>([]);
  const [selectedUserHistory, setSelectedUserHistory] = useState<AdminInterviewHistoryItem[]>([]);
  const [selectedUserEmail, setSelectedUserEmail] = useState("");
  const [loginEmail, setLoginEmail] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [loginCode, setLoginCode] = useState("");
  const [isRequestingAdminCode, setIsRequestingAdminCode] = useState(false);
  const [isSubmittingAdminLogin, setIsSubmittingAdminLogin] = useState(false);
  const [adminCodeCooldownSeconds, setAdminCodeCooldownSeconds] = useState(0);
  const [creditUser, setCreditUser] = useState("");
  const [creditAmount, setCreditAmount] = useState("1");
  const [creditReason, setCreditReason] = useState("manual_grant");
  const [creditNote, setCreditNote] = useState("");
  const [noteCategory, setNoteCategory] = useState("general");
  const [noteContent, setNoteContent] = useState("");
  const [noteSessionId, setNoteSessionId] = useState("");
  const [refundReason, setRefundReason] = useState("refund_request");
  const [refundDescription, setRefundDescription] = useState("");
  const [refundAmountYuan, setRefundAmountYuan] = useState("");
  const [refundCreditAdjustment, setRefundCreditAdjustment] = useState("");
  const [refundSessionId, setRefundSessionId] = useState("");
  const [selectedReport, setSelectedReport] = useState<AdminInterviewReport | null>(null);
  const [reportMessage, setReportMessage] = useState("");
  const [message, setMessage] = useState("请使用管理员邮箱、密码和邮箱验证码进入后台。");
  const [isLoading, setIsLoading] = useState(false);
  const [activeAdminSection, setActiveAdminSection] = useState<AdminSectionKey>("overview");

  const dashboardChartOptions = useMemo(() => {
    if (!dashboardStats) {
      return null;
    }
    return {
      trend: lineDashboardOption(dashboardStats),
      moduleMix: donutDashboardOption(dashboardStats.interview_type_distribution, ["#174ea6", "#b3261e", "#0b8043", "#f29900"]),
      sessionStatus: donutDashboardOption(dashboardStats.session_status_distribution, ["#174ea6", "#0b8043", "#f29900", "#b3261e"]),
      aiQuality: barDashboardOption(dashboardStats.ai_call_success_distribution, "AI 调用", "#174ea6"),
      loginOutcome: barDashboardOption(dashboardStats.login_outcome_distribution, "登录", "#b3261e"),
      refunds: donutDashboardOption(dashboardStats.refund_status_distribution, ["#f29900", "#174ea6", "#0b8043", "#b3261e"]),
    };
  }, [dashboardStats]);

  useEffect(() => {
    if (!getCookie(CSRF_COOKIE_NAME)) {
      return;
    }
    void loadCurrentUser();
  }, []);

  useEffect(() => {
    const section = adminSectionFromHash(window.location.hash);
    if (section) {
      setActiveAdminSection(section);
    }
  }, []);

  useEffect(() => {
    if (!currentUser) {
      return;
    }
    const previousBodyOverflow = document.body.style.overflow;
    const previousHtmlOverflow = document.documentElement.style.overflow;
    document.body.style.overflow = "hidden";
    document.documentElement.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousBodyOverflow;
      document.documentElement.style.overflow = previousHtmlOverflow;
    };
  }, [currentUser]);

  useEffect(() => {
    const normalizedEmail = normalizeAdminEmail(loginEmail);
    if (!normalizedEmail) {
      setAdminCodeCooldownSeconds(0);
      return;
    }
    const nextAllowedAt = Number.parseInt(window.localStorage.getItem(adminCodeCooldownKey(normalizedEmail)) ?? "0", 10);
    setAdminCodeCooldownSeconds(secondsUntil(nextAllowedAt));
  }, [loginEmail]);

  useEffect(() => {
    if (adminCodeCooldownSeconds <= 0) {
      return;
    }
    const timer = window.setInterval(() => {
      const nextAllowedAt = Number.parseInt(window.localStorage.getItem(adminCodeCooldownKey(loginEmail)) ?? "0", 10);
      setAdminCodeCooldownSeconds(secondsUntil(nextAllowedAt));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [adminCodeCooldownSeconds, loginEmail]);

  function startAdminCodeCooldown(seconds = ADMIN_CODE_COOLDOWN_SECONDS) {
    const normalizedEmail = normalizeAdminEmail(loginEmail);
    if (!normalizedEmail) {
      return;
    }
    const safeSeconds = Math.max(1, seconds);
    window.localStorage.setItem(adminCodeCooldownKey(normalizedEmail), String(Date.now() + safeSeconds * 1000));
    setAdminCodeCooldownSeconds(safeSeconds);
  }

  function formatDateTime(value: string) {
    return new Date(value).toLocaleString("zh-CN");
  }

  function formatCents(value?: number | null, currency = "CNY") {
    if (value == null) {
      return "未记录金额";
    }
    const amount = (value / 100).toFixed(2);
    return currency === "CNY" ? `¥${amount}` : `${currency} ${amount}`;
  }

  function formatDashboardRate(value?: number | null) {
    return value == null ? "暂无" : `${value}%`;
  }

  const selectedCreditReason = creditReasonOptions.find((option) => option.value === creditReason) ?? creditReasonOptions[0];

  const consoleLayoutRef = useRef<HTMLDivElement | null>(null);

  function selectAdminSection(section: AdminSectionKey) {
    setActiveAdminSection(section);
    window.history.replaceState(null, "", `#admin-${section}`);
    window.requestAnimationFrame(() => {
      consoleLayoutRef.current?.scrollTo({ top: 0 });
    });
  }

  function parseOptionalInteger(rawValue: string) {
    const trimmed = rawValue.trim();
    if (!trimmed) {
      return undefined;
    }
    const value = Number.parseInt(trimmed, 10);
    return Number.isNaN(value) ? null : value;
  }

  function parseOptionalAmountCents(rawValue: string) {
    const trimmed = rawValue.trim();
    if (!trimmed) {
      return undefined;
    }
    const value = Number.parseFloat(trimmed);
    return Number.isNaN(value) || value < 0 ? null : Math.round(value * 100);
  }

  async function loadCurrentUser() {
    setIsLoading(true);
    let response: Response;
    try {
      response = await fetch("/api/auth/me", { credentials: "include" });
    } catch {
      setCurrentUser(null);
      setIsLoading(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    if (!response.ok) {
      setCurrentUser(null);
      setIsLoading(false);
      setMessage("请使用管理员邮箱、密码和邮箱验证码进入后台。");
      return;
    }

    const user = (await response.json()) as CurrentUser;
    if (user.role !== "admin") {
      setCurrentUser(null);
      setIsLoading(false);
      setMessage("当前账户不是管理员，请使用管理员账号登录。");
      return;
    }

    setCurrentUser(user);
    setIsLoading(false);
    setMessage(`已进入后台：${user.email}`);
    await Promise.allSettled([
      loadProviders(),
      loadDashboardStats(),
      loadSystemConfigs(),
      loadAuditLogs(),
      loadAiCallLogs(),
      loadContentSafetyLogs(),
      loadAuthLoginLogs(),
      loadRefundCases(),
    ]);
  }

  async function loadProviders() {
    const response = await fetch("/api/ai-providers", { credentials: "include" });
    if (!response.ok) {
      setMessage("AI 服务状态读取失败。");
      return;
    }
    setProviders((await response.json()) as ProviderConfig[]);
  }

  async function loadAuditLogs() {
    const response = await fetch("/api/admin/audit-logs", { credentials: "include" });
    if (!response.ok) {
      return;
    }
    setAuditLogs((await response.json()) as AdminAuditLog[]);
  }

  async function loadAiCallLogs() {
    const response = await fetch("/api/admin/ai-call-logs", { credentials: "include" });
    if (!response.ok) {
      return;
    }
    setAiCallLogs((await response.json()) as AICallLogEntry[]);
  }

  async function loadContentSafetyLogs() {
    const response = await fetch("/api/admin/content-safety-logs", { credentials: "include" });
    if (!response.ok) {
      return;
    }
    setContentSafetyLogs((await response.json()) as ContentSafetyLogEntry[]);
  }

  async function loadAuthLoginLogs(userEmail?: string) {
    const normalizedEmail = userEmail?.trim();
    const url = normalizedEmail
      ? `/api/admin/users/${encodeURIComponent(normalizedEmail)}/auth-login-logs`
      : "/api/admin/auth-login-logs";
    const response = await fetch(url, { credentials: "include" });
    if (!response.ok) {
      return;
    }
    setAuthLoginLogs((await response.json()) as AuthLoginLogEntry[]);
  }

  async function loadCustomerServiceNotes(userEmail?: string) {
    const normalizedEmail = userEmail?.trim();
    if (!normalizedEmail) {
      setCustomerServiceNotes([]);
      return;
    }
    const response = await fetch(`/api/admin/users/${encodeURIComponent(normalizedEmail)}/notes`, {
      credentials: "include",
    });
    if (!response.ok) {
      return;
    }
    setCustomerServiceNotes((await response.json()) as CustomerServiceNoteEntry[]);
  }

  async function loadRefundCases(userEmail?: string) {
    const normalizedEmail = userEmail?.trim();
    const url = normalizedEmail
      ? `/api/admin/users/${encodeURIComponent(normalizedEmail)}/refund-cases`
      : "/api/admin/refund-cases";
    const response = await fetch(url, { credentials: "include" });
    if (!response.ok) {
      return;
    }
    setRefundCases((await response.json()) as RefundCaseEntry[]);
  }

  async function loadSystemConfigs() {
    const response = await fetch("/api/admin/system-configs", { credentials: "include" });
    if (!response.ok) {
      return;
    }
    setSystemConfigs((await response.json()) as SystemConfig[]);
  }

  async function loadDashboardStats() {
    const response = await fetch("/api/admin/stats", { credentials: "include" });
    if (!response.ok) {
      return;
    }
    setDashboardStats((await response.json()) as AdminDashboardStats);
  }

  async function loadCreditLedger(userEmail = creditUser) {
    if (!userEmail) {
      setCreditLedger([]);
      return;
    }

    const response = await fetch(`/api/admin/users/${encodeURIComponent(userEmail)}/credit-ledger`, {
      credentials: "include",
    });
    if (!response.ok) {
      return;
    }
    setCreditLedger((await response.json()) as CreditLedgerEntry[]);
  }

  async function searchUsers(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    const query = userSearchQuery.trim();
    if (!query) {
      setMessage("请先输入用户邮箱或邮箱关键词。");
      return;
    }

    const response = await fetch(`/api/admin/users/search?query=${encodeURIComponent(query)}`, {
      credentials: "include",
    });
    if (!response.ok) {
      setMessage("用户搜索失败，请确认管理员会话仍然有效。");
      return;
    }

    const users = (await response.json()) as AdminUserSearchItem[];
    setUserSearchResults(users);
    setMessage(users.length > 0 ? `找到 ${users.length} 个用户记录。` : "没有匹配的用户。");
    if (users.length > 0) {
      await loadUserHistory(users[0].email);
    }
  }

  async function loadUserHistory(userEmail: string) {
    setSelectedUserEmail(userEmail);
    setCreditUser(userEmail);
    setSelectedReport(null);
    setReportMessage("");
    await Promise.all([
      loadCreditLedger(userEmail),
      loadCustomerServiceNotes(userEmail),
      loadRefundCases(userEmail),
      loadAuthLoginLogs(userEmail),
    ]);
    const response = await fetch(`/api/admin/users/${encodeURIComponent(userEmail)}/interviews`, {
      credentials: "include",
    });
    if (!response.ok) {
      setSelectedUserHistory([]);
      setMessage("用户训练记录读取失败。");
      return;
    }
    setSelectedUserHistory((await response.json()) as AdminInterviewHistoryItem[]);
  }

  async function loadInterviewReport(userEmail: string, sessionId: string) {
    setSelectedReport(null);
    setReportMessage("正在读取报告。");
    const response = await fetch(
      `/api/admin/users/${encodeURIComponent(userEmail)}/interviews/${encodeURIComponent(sessionId)}/report`,
      { credentials: "include" },
    );
    if (!response.ok) {
      setReportMessage("该训练尚未生成报告，或当前管理员会话已失效。");
      return;
    }

    setSelectedReport((await response.json()) as AdminInterviewReport);
    setReportMessage("报告已读取，可用于售后复核和争议追溯。");
  }

  async function requestAdminCode() {
    if (!loginEmail) {
      setMessage("请先填写管理员邮箱。");
      return;
    }
    if (adminCodeCooldownSeconds > 0) {
      setMessage(`验证码已发送, ${adminCodeCooldownSeconds} 秒后可以重新获取`);
      return;
    }

    setIsRequestingAdminCode(true);
    setMessage("正在发送管理员验证码...");
    let response: Response;
    try {
      response = await fetch("/api/auth/email-code/request", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: normalizeAdminEmail(loginEmail) }),
      });
    } catch {
      setIsRequestingAdminCode(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const data = (await response.json().catch(() => ({}))) as AdminLoginResponse;
    setIsRequestingAdminCode(false);
    if (!response.ok) {
      if (response.status === 429) {
        const retryAfter = retryAfterSeconds(response);
        startAdminCodeCooldown(retryAfter);
        setMessage(`获取太频繁, 请 ${retryAfter} 秒后再试`);
        return;
      }
      setMessage(`验证码发送失败: ${getApiErrorMessage(data, "请稍后再试")}`);
      return;
    }

    startAdminCodeCooldown(ADMIN_CODE_COOLDOWN_SECONDS);
    setLoginCode(data.dev_code ?? "");
    setMessage(data.dev_code ? `开发验证码: ${data.dev_code}` : "验证码已发送, 5 分钟内有效, 请查看邮箱");
  }

  async function submitAdminLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isSubmittingAdminLogin) {
      return;
    }
    setIsSubmittingAdminLogin(true);
    let response: Response;
    try {
      response = await fetch("/api/auth/admin/login", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: loginEmail, password: loginPassword, code: loginCode }),
      });
    } catch {
      setIsSubmittingAdminLogin(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const data = (await response.json().catch(() => ({}))) as AdminLoginResponse;
    if (!response.ok || !data.access_token) {
      setIsSubmittingAdminLogin(false);
      setMessage(`后台登录失败：${getApiErrorMessage(data, "请检查邮箱、密码和验证码。")}`);
      return;
    }

    await loadCurrentUser();
    setIsSubmittingAdminLogin(false);
  }

  async function submitCreditGrant(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const amount = Number.parseInt(creditAmount, 10);
    const reason = creditReason.trim() || (amount > 0 ? "manual_grant" : "manual_adjustment");
    const note = creditNote.trim();
    if (!creditUser || Number.isNaN(amount) || amount === 0 || reason.length < 2) {
      setMessage("请填写用户邮箱、非 0 次数和调整原因。");
      return;
    }

    const response = await fetch(`/api/admin/users/${encodeURIComponent(creditUser)}/credits`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ change_amount: amount, reason, note: note || undefined }),
    });
    const data = await response.json();
    if (!response.ok) {
      setMessage(`次数调整失败：${getApiErrorMessage(data, "请检查用户邮箱和次数。")}`);
      return;
    }

    setMessage(`${creditUser} 已调整 ${amount} 次，当前余额 ${data.balance_after}。`);
    setCreditAmount("1");
    setCreditReason("manual_grant");
    setCreditNote("");
    await Promise.all([loadDashboardStats(), loadAuditLogs(), loadCreditLedger(creditUser)]);
  }

  async function submitCustomerServiceNote(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedUserEmail) {
      setMessage("请先在用户检索里选择一个用户。");
      return;
    }
    const content = noteContent.trim();
    const category = noteCategory.trim() || "general";
    if (content.length < 2) {
      setMessage("客服备注至少需要 2 个字符。");
      return;
    }

    const response = await fetch(`/api/admin/users/${encodeURIComponent(selectedUserEmail)}/notes`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        category,
        content,
        related_session_id: noteSessionId.trim() || undefined,
      }),
    });
    const data = await response.json();
    if (!response.ok) {
      setMessage(`客服备注保存失败：${getApiErrorMessage(data, "请检查备注内容。")}`);
      return;
    }

    setNoteCategory("general");
    setNoteContent("");
    setNoteSessionId("");
    setMessage(`已为 ${selectedUserEmail} 添加客服备注。`);
    await Promise.all([loadCustomerServiceNotes(selectedUserEmail), loadAuditLogs()]);
  }

  async function submitRefundCase(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedUserEmail) {
      setMessage("请先在用户检索里选择一个用户。");
      return;
    }
    const reason = refundReason.trim();
    const description = refundDescription.trim();
    const amountCents = parseOptionalAmountCents(refundAmountYuan);
    const creditAdjustment = parseOptionalInteger(refundCreditAdjustment);
    if (reason.length < 2 || description.length < 2) {
      setMessage("退款纠纷需要填写原因和处理描述。");
      return;
    }
    if (amountCents === null) {
      setMessage("退款金额需要填写为合法的非负数字，例如 19.90。");
      return;
    }
    if (creditAdjustment === null) {
      setMessage("次数补偿需要填写整数，例如 1、0 或 -1。");
      return;
    }

    const response = await fetch(`/api/admin/users/${encodeURIComponent(selectedUserEmail)}/refund-cases`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        reason,
        description,
        amount_cents: amountCents,
        currency: "CNY",
        credit_adjustment: creditAdjustment,
        related_session_id: refundSessionId.trim() || undefined,
      }),
    });
    const data = await response.json();
    if (!response.ok) {
      setMessage(`退款纠纷记录创建失败：${getApiErrorMessage(data, "请检查纠纷记录内容。")}`);
      return;
    }

    setRefundReason("refund_request");
    setRefundDescription("");
    setRefundAmountYuan("");
    setRefundCreditAdjustment("");
    setRefundSessionId("");
    setMessage(`已创建 ${selectedUserEmail} 的退款纠纷记录。`);
    await Promise.all([loadDashboardStats(), loadRefundCases(selectedUserEmail), loadAuditLogs()]);
  }

  async function updateRefundCaseStatus(refundCase: RefundCaseEntry, statusValue: string) {
    const response = await fetch(`/api/admin/refund-cases/${encodeURIComponent(refundCase.id)}`, {
      method: "PUT",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        status: statusValue,
        resolution: statusValue === "resolved" ? refundCase.resolution || "已人工处理完成" : refundCase.resolution,
      }),
    });
    const data = await response.json();
    if (!response.ok) {
      setMessage(`退款纠纷状态更新失败：${getApiErrorMessage(data, "请稍后重试。")}`);
      return;
    }

    setMessage(`退款纠纷 ${refundCase.id} 已更新为 ${statusValue}。`);
    await Promise.all([loadDashboardStats(), loadRefundCases(selectedUserEmail || undefined), loadAuditLogs()]);
  }

  async function toggleProvider(provider: ProviderConfig) {
    const response = await fetch(`/api/ai-providers/${encodeURIComponent(provider.id)}`, {
      method: "PUT",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ enabled: !provider.enabled }),
    });
    if (!response.ok) {
      setMessage("模型启停更新失败。");
      return;
    }
    await loadProviders();
    await loadAuditLogs();
    setMessage(`${provider.id} 已${provider.enabled ? "停用" : "启用"}。`);
  }

  async function testProvider(provider: ProviderConfig) {
    setProviderTestResults((previous) => ({ ...previous, [provider.id]: "测试中" }));
    const response = await fetch(`/api/ai-providers/${encodeURIComponent(provider.id)}/test`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders(),
    });
    const data = await response.json();
    const resultDetail = getApiErrorMessage(data, "服务测试未返回明确结果。");
    const resultText = response.ok
      ? `${data.success ? "通过" : "未通过"}：${resultDetail}`
      : `测试失败：${resultDetail}`;
    setProviderTestResults((previous) => ({ ...previous, [provider.id]: resultText }));
    await loadAuditLogs();
  }

  async function updateUserStatus(user: AdminUserSearchItem, isActive: boolean) {
    const response = await fetch(`/api/admin/users/${encodeURIComponent(user.email)}/status`, {
      method: "PUT",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ is_active: isActive, reason: isActive ? "manual_restore" : "manual_disable" }),
    });
    const data = await response.json();
    if (!response.ok) {
      setMessage(`用户状态更新失败：${getApiErrorMessage(data, "请稍后重试。")}`);
      return;
    }
    setMessage(`${user.email} 已${isActive ? "启用" : "禁用"}。`);
    await Promise.all([loadDashboardStats(), searchUsers(), loadAuditLogs()]);
  }

  async function updateUserRole(user: AdminUserSearchItem, role: "user" | "admin") {
    const response = await fetch(`/api/admin/users/${encodeURIComponent(user.email)}/role`, {
      method: "PUT",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ role, reason: role === "admin" ? "grant_admin" : "revoke_admin" }),
    });
    const data = (await response.json()) as { role?: string; detail?: string };
    if (!response.ok) {
      setMessage(`用户角色更新失败：${getApiErrorMessage(data, "请稍后重试。")}`);
      return;
    }
    setUserSearchResults((previous) =>
      previous.map((item) => (item.email === user.email ? { ...item, role: data.role ?? role } : item)),
    );
    setMessage(`${user.email} 已${role === "admin" ? "设为管理员" : "撤销管理员"}，该账号需要重新登录后生效。`);
    await Promise.all([loadDashboardStats(), loadAuditLogs()]);
  }

  function configInputValue(value: SystemConfig["value"]) {
    if (typeof value === "string") {
      return value;
    }
    if (typeof value === "number" || typeof value === "boolean") {
      return String(value);
    }
    return JSON.stringify(value);
  }

  function parseConfigInput(currentValue: SystemConfig["value"], rawValue: string) {
    if (typeof currentValue === "boolean") {
      return rawValue === "true";
    }
    if (typeof currentValue === "number") {
      const parsed = Number(rawValue);
      if (Number.isNaN(parsed)) {
        throw new Error("invalid_number_config");
      }
      return parsed;
    }
    if (Array.isArray(currentValue) || (currentValue && typeof currentValue === "object")) {
      return JSON.parse(rawValue);
    }
    return rawValue;
  }

  async function updateSystemConfig(config: SystemConfig, rawValue: string) {
    let value: SystemConfig["value"];
    try {
      value = parseConfigInput(config.value, rawValue);
    } catch {
      setMessage(`${config.key} 需要合法 JSON。`);
      return;
    }
    const response = await fetch(`/api/admin/system-configs/${encodeURIComponent(config.key)}`, {
      method: "PUT",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ value }),
    });
    const data = await response.json();
    if (!response.ok) {
      setMessage(`系统配置保存失败：${getApiErrorMessage(data, "请检查配置值。")}`);
      return;
    }
    setMessage(`已更新系统配置：${data.key}`);
    await Promise.all([loadSystemConfigs(), loadAuditLogs()]);
  }

  async function refreshAdminData() {
    await Promise.allSettled([
      loadProviders(),
      loadDashboardStats(),
      loadSystemConfigs(),
      loadAuditLogs(),
      loadAiCallLogs(),
      loadContentSafetyLogs(),
      loadCreditLedger(),
      loadAuthLoginLogs(selectedUserEmail || undefined),
      loadRefundCases(selectedUserEmail || undefined),
      loadCustomerServiceNotes(selectedUserEmail || undefined),
    ]);
  }

  async function logout() {
    await fetch("/api/auth/logout", { method: "POST", credentials: "include", headers: csrfHeaders() });
    setCurrentUser(null);
    setProviders([]);
    setAuditLogs([]);
    setCreditLedger([]);
    setAiCallLogs([]);
    setContentSafetyLogs([]);
    setAuthLoginLogs([]);
    setCustomerServiceNotes([]);
    setRefundCases([]);
    setSystemConfigs([]);
    setDashboardStats(null);
    setProviderTestResults({});
    setUserSearchResults([]);
    setSelectedUserHistory([]);
    setSelectedUserEmail("");
    setSelectedReport(null);
    setReportMessage("");
    setNoteCategory("general");
    setNoteContent("");
    setNoteSessionId("");
    setRefundReason("refund_request");
    setRefundDescription("");
    setRefundAmountYuan("");
    setRefundCreditAdjustment("");
    setRefundSessionId("");
    setMessage("已退出后台，请重新完成管理员后台登录。");
    window.location.assign("/");
  }

  if (!currentUser) {
    return (
      <main className="auth-page admin-auth-page">
        <div className="auth-ambient" aria-hidden="true">
          <span className="auth-glow auth-glow-blue" />
          <span className="auth-glow auth-glow-lime" />
          <span className="auth-glow auth-glow-fog" />
        </div>
        <a className="back-link auth-anim-back admin-auth-back" href="/">
          <AppIcon icon="lucide:arrow-left" size={18} />
          返回首页
        </a>
        <section className="auth-shell admin-auth-shell">
          <div className="auth-narrative admin-auth-narrative">
            <div className="admin-auth-brand">
              <BrandLogo size={30} />
              <span>面霸练习生</span>
            </div>
            <span className="eyebrow">Admin Console</span>
            <h1 className="auth-title-login">
              <span className="auth-title-line admin-auth-title-single">管理员后台登录</span>
            </h1>
            <p>使用管理员邮箱、密码和邮箱验证码进入内部后台，集中处理用户次数、AI 服务状态、售后追踪和安全审计。</p>
            <div className="auth-status-card admin-auth-status-card" aria-label="后台能力状态">
              <span>
                <AppIcon icon="lucide:shield-check" size={18} />
                权限校验
              </span>
              <span>
                <AppIcon icon="lucide:mail-check" size={18} />
                邮箱验证
              </span>
              <span>
                <AppIcon icon="lucide:file-clock" size={18} />
                操作留痕
              </span>
            </div>
          </div>

          <form className="auth-card auth-glass-card admin-auth-card" onSubmit={submitAdminLogin}>
            <div className="auth-card-heading admin-auth-card-heading">
              <span>{isLoading ? "正在检查会话" : "管理员登录"}</span>
            </div>
            <div className="auth-form-body">
              <label>
                管理员邮箱
                <div className="input-shell">
                  <AppIcon icon="lucide:mail" size={18} />
                  <input type="email" value={loginEmail} onChange={(event) => setLoginEmail(event.target.value)} placeholder="admin@example.com" autoComplete="username" required />
                </div>
              </label>
              <label>
                密码
                <div className="input-shell">
                  <AppIcon icon="lucide:key-round" size={18} />
                  <input type="password" value={loginPassword} onChange={(event) => setLoginPassword(event.target.value)} placeholder="至少 8 位" minLength={8} autoComplete="current-password" required />
                </div>
              </label>
              <label>
                邮箱验证码
                <div className="code-row">
                  <div className="input-shell">
                    <AppIcon icon="lucide:key-round" size={18} />
                    <input value={loginCode} onChange={(event) => setLoginCode(event.target.value)} placeholder="6 位验证码" minLength={6} maxLength={6} autoComplete="one-time-code" required />
                  </div>
                  <button type="button" className="code-button admin-auth-code-button" onClick={requestAdminCode} disabled={isRequestingAdminCode || adminCodeCooldownSeconds > 0}>
                    {isRequestingAdminCode ? "发送中" : adminCodeCooldownSeconds > 0 ? `${adminCodeCooldownSeconds}s` : "获取"}
                  </button>
                </div>
              </label>
              <button type="submit" className="auth-submit admin-auth-submit" disabled={isLoading || isSubmittingAdminLogin}>
                {isLoading ? "检查中" : isSubmittingAdminLogin ? "登录中" : "进入后台"}
              </button>
              <p className="auth-message" role="status" aria-live="polite">{message}</p>
            </div>
          </form>
        </section>
      </main>
    );
  }

  const adminStatusMessage = message.startsWith("已进入后台") ? "后台在线，操作会记录到审计日志" : message;

  return (
    <main className="workspace-page admin-page admin-page--authed" data-admin-section={activeAdminSection}>
      <aside className="admin-sidebar" aria-label="管理员后台侧栏">
        <a href="/" className="admin-sidebar-brand">
          <BrandLogo size={30} />
          <span>面霸练习生</span>
        </a>
        <nav className="admin-sidebar-nav" aria-label="后台导航">
          {adminSectionNavItems.map((item) => (
            <button
              type="button"
              className={activeAdminSection === item.key ? "is-active" : ""}
              key={item.key}
              onClick={() => selectAdminSection(item.key)}
            >
              <AppIcon icon={item.icon} size={20} />
              <span>{item.label}</span>
            </button>
          ))}
        </nav>
        <div className="admin-sidebar-status" aria-live="polite">
          <span>当前账号</span>
          <strong>{currentUser.email}</strong>
          <p>{adminStatusMessage}</p>
        </div>
        <div className="admin-sidebar-actions">
          <button type="button" onClick={refreshAdminData}>
            <AppIcon icon="lucide:refresh-cw" size={18} />
            刷新数据
          </button>
          <button type="button" className="admin-sidebar-logout" onClick={() => void logout()}>
            <AppIcon icon="lucide:log-out" size={18} />
            退出
          </button>
        </div>
      </aside>
      <div className="admin-console-layout" ref={consoleLayoutRef}>
        <section className="admin-console-main">
          {dashboardStats && dashboardChartOptions && (
            <section className="admin-dashboard admin-section-view admin-section-view--overview" id="admin-overview">
              <div className="admin-section-heading admin-dashboard-heading">
                <div>
                  <span className="eyebrow">Data Dashboard</span>
                  <h2>运营数据看板</h2>
                  <p>聚合数据库里的用户、训练、报告、登录、AI 调用和售后纠纷数据，用来判断推广效果和服务稳定性。</p>
                </div>
                <span className={dashboardStats.database_ready ? "admin-dashboard-badge" : "admin-dashboard-badge is-muted"}>
                  {dashboardStats.database_ready ? "数据库已连接" : "等待数据库迁移"}
                </span>
              </div>

              <div className="admin-kpi-grid">
                <article>
                  <span>总用户</span>
                  <strong>{dashboardStats.overview.total_users.toLocaleString("zh-CN")}</strong>
                  <em>启用 {dashboardStats.overview.active_users} / 停用 {dashboardStats.overview.disabled_users}</em>
                </article>
                <article>
                  <span>训练场次</span>
                  <strong>{dashboardStats.overview.total_sessions.toLocaleString("zh-CN")}</strong>
                  <em>今日 {dashboardStats.overview.today_sessions} / 进行中 {dashboardStats.overview.active_sessions}</em>
                </article>
                <article>
                  <span>报告产出</span>
                  <strong>{dashboardStats.overview.total_reports.toLocaleString("zh-CN")}</strong>
                  <em>均分 {dashboardStats.overview.average_report_score ?? "暂无"}</em>
                </article>
                <article>
                  <span>剩余次数</span>
                  <strong>{dashboardStats.overview.total_credit_balance.toLocaleString("zh-CN")}</strong>
                  <em>累计发放 {dashboardStats.overview.total_credit_granted}</em>
                </article>
                <article>
                  <span>AI 成功率</span>
                  <strong>{formatDashboardRate(dashboardStats.overview.ai_success_rate)}</strong>
                  <em>失败登录 {dashboardStats.overview.failed_login_count}</em>
                </article>
                <article>
                  <span>售后风险</span>
                  <strong>{dashboardStats.overview.open_refund_cases}</strong>
                  <em>待处理退款 / 纠纷</em>
                </article>
              </div>

              {!dashboardStats.database_ready ? (
                <p className="admin-empty-text">统计接口已经就绪，完成数据库迁移后会自动显示图表。</p>
              ) : (
                <>
                  <div className="admin-chart-grid">
                    <article className="admin-chart-card admin-chart-card--wide">
                      <div>
                        <h3>近 14 天使用趋势</h3>
                        <span>新增用户、训练场次和报告产出</span>
                      </div>
                      <AdminChart option={dashboardChartOptions.trend} height={320} />
                    </article>
                    <article className="admin-chart-card">
                      <div>
                        <h3>训练模块占比</h3>
                        <span>四类面试的真实使用分布</span>
                      </div>
                      <AdminChart option={dashboardChartOptions.moduleMix} />
                    </article>
                    <article className="admin-chart-card">
                      <div>
                        <h3>会话状态</h3>
                        <span>完成、进行中与异常状态</span>
                      </div>
                      <AdminChart option={dashboardChartOptions.sessionStatus} />
                    </article>
                    <article className="admin-chart-card">
                      <div>
                        <h3>AI 调用质量</h3>
                        <span>模型、ASR、TTS 调用成功情况</span>
                      </div>
                      <AdminChart option={dashboardChartOptions.aiQuality} />
                    </article>
                    <article className="admin-chart-card">
                      <div>
                        <h3>登录结果</h3>
                        <span>账户登录与验证码闭环是否稳定</span>
                      </div>
                      <AdminChart option={dashboardChartOptions.loginOutcome} />
                    </article>
                    <article className="admin-chart-card">
                      <div>
                        <h3>退款纠纷状态</h3>
                        <span>跟进中的售后记录分布</span>
                      </div>
                      <AdminChart option={dashboardChartOptions.refunds} />
                    </article>
                  </div>

                  <section className="admin-top-users">
                    <div>
                      <span className="eyebrow">User Usage</span>
                      <h3>用户使用情况排行</h3>
                    </div>
                    {dashboardStats.top_users.length === 0 ? (
                      <p className="admin-empty-text">暂无训练记录。</p>
                    ) : (
                      <div className="admin-top-user-list">
                        {dashboardStats.top_users.map((user) => (
                          <article key={user.email}>
                            <strong>{user.email}</strong>
                            <span>{user.completed_interviews}/{user.total_interviews} 次完成</span>
                            <em>余额 {user.credit_balance} 次</em>
                            <small>{user.last_interview_at ? formatDateTime(user.last_interview_at) : "暂无训练"}</small>
                          </article>
                        ))}
                      </div>
                    )}
                  </section>
                </>
              )}
            </section>
          )}

          <section className="admin-workbench admin-section-view admin-section-view--credits" id="admin-credits">
            <form className="admin-panel" onSubmit={submitCreditGrant}>
              <h2>发放 / 调整次数</h2>
              <label>
                用户邮箱
                <input type="email" value={creditUser} onChange={(event) => setCreditUser(event.target.value)} placeholder="user@example.com" required />
              </label>
              <label>
                次数变化
                <input type="number" value={creditAmount} onChange={(event) => setCreditAmount(event.target.value)} required />
              </label>
              <label>
                调整原因
                <select value={creditReason} onChange={(event) => setCreditReason(event.target.value)} required>
                  {creditReasonOptions.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
                <small className="admin-field-help">{selectedCreditReason.help}</small>
              </label>
              <label>
                处理备注
                <input value={creditNote} onChange={(event) => setCreditNote(event.target.value)} placeholder="例如：微信沟通后补发 1 次" maxLength={240} />
              </label>
              <div className="admin-action-row">
                <button type="submit" className="admin-primary-button">提交调整</button>
                <button type="button" onClick={() => loadCreditLedger()}>
                  <AppIcon icon="lucide:file-clock" size={18} />
                  查看流水
                </button>
              </div>
              <div className="admin-ledger-list">
                {creditLedger.slice(0, 4).map((entry) => (
                  <div className="admin-ledger-item" key={entry.id}>
                    <strong>{entry.change_amount > 0 ? `+${entry.change_amount}` : entry.change_amount}</strong>
                    <span>{entry.reason} · 余额 {entry.balance_after}{entry.note ? ` · ${entry.note}` : ""}</span>
                  </div>
                ))}
              </div>
            </form>
            <article className="admin-panel admin-trace-summary">
              <h2>运营追溯</h2>
              <div className="admin-mini-metrics">
                <span>
                  <b>{authLoginLogs.filter((entry) => !entry.success).length}</b>
                  登录失败
                </span>
                <span>
                  <b>{refundCases.filter((entry) => entry.status !== "resolved").length}</b>
                  待处理退款
                </span>
                <span>
                  <b>{aiCallLogs.filter((entry) => !entry.success).length}</b>
                  AI 调用失败
                </span>
                <span>
                  <b>{contentSafetyLogs.filter((entry) => entry.action !== "allowed").length}</b>
                  安全拦截
                </span>
              </div>
              <p>
                售后纠纷优先从训练报告、次数流水、登录日志、内容安全日志和 AI 调用日志交叉核对，避免只凭聊天截图判断。
              </p>
            </article>
          </section>

          <section className="admin-provider-table admin-user-table admin-section-view admin-section-view--users" id="admin-users">
            <div className="admin-section-heading">
              <span className="eyebrow">User Operations</span>
              <h2>用户检索与训练追踪</h2>
            </div>
            <form className="admin-search-form" onSubmit={searchUsers}>
              <label>
                用户邮箱
                <input
                  type="search"
                  value={userSearchQuery}
                  onChange={(event) => setUserSearchQuery(event.target.value)}
                  placeholder="输入完整邮箱或邮箱关键词"
                />
              </label>
              <button type="submit" className="admin-primary-button">
                <AppIcon icon="lucide:users" size={18} />
                搜索用户
              </button>
            </form>
            <div className="admin-user-grid">
              <div className="admin-user-results">
                {userSearchResults.length === 0 && <p className="admin-empty-text">搜索后会显示用户余额、训练次数和最近训练时间。</p>}
                {userSearchResults.map((user) => (
                  <article className="admin-user-result" key={user.email}>
                    <button
                      type="button"
                      className={selectedUserEmail === user.email ? "is-selected" : ""}
                      onClick={() => void loadUserHistory(user.email)}
                    >
                      <span>
                        <strong>{user.email}</strong>
                        <em>{user.role} / {user.is_active ? "启用中" : "已禁用"} / 余额 {user.credit_balance} 次</em>
                      </span>
                      <b>{user.completed_interviews}/{user.total_interviews}</b>
                    </button>
                    <div className="admin-user-actions">
                      <button
                        type="button"
                        className="admin-history-action"
                        onClick={() => void updateUserStatus(user, !user.is_active)}
                      >
                        {user.is_active ? "禁用" : "启用"}
                      </button>
                      <button
                        type="button"
                        className="admin-history-action"
                        onClick={() => void updateUserRole(user, user.role === "admin" ? "user" : "admin")}
                      >
                        {user.role === "admin" ? "撤销管理员" : "设为管理员"}
                      </button>
                    </div>
                  </article>
                ))}
              </div>
              <div className="admin-user-history">
                <div className="admin-user-history-head">
                  <span>{selectedUserEmail || "未选择用户"}</span>
                  <em>{selectedUserHistory.length} 条训练记录</em>
                </div>
                {selectedUserHistory.length === 0 ? (
                  <p className="admin-empty-text">选择用户后，这里会显示训练模块、进度、报告分数和创建时间。</p>
                ) : (
                  selectedUserHistory.slice(0, 8).map((item) => (
                    <article className={selectedReport?.session_id === item.session_id ? "is-active-report" : ""} key={item.session_id}>
                      <div>
                        <strong>{item.interview_type}</strong>
                        <span>{item.status} / {item.current_step_index + 1}-{item.total_steps}</span>
                      </div>
                      <em>{item.report_total_score ?? "未出分"}</em>
                      <small>{new Date(item.created_at).toLocaleString("zh-CN")}</small>
                      <button
                        type="button"
                        className="admin-history-action"
                        disabled={item.report_total_score == null}
                        onClick={() => void loadInterviewReport(selectedUserEmail, item.session_id)}
                      >
                        查看报告
                      </button>
                    </article>
                  ))
                )}
              </div>
            </div>
            {(reportMessage || selectedReport) && (
              <div className="admin-report-detail">
                <div className="admin-report-detail-head">
                  <div>
                    <span className="eyebrow">Report Review</span>
                    <h3>{selectedReport ? `${selectedReport.user_email} · ${selectedReport.interview_type}` : "报告复核"}</h3>
                  </div>
                  {selectedReport && <strong>{selectedReport.total_score}</strong>}
                </div>
                {reportMessage && <p className="admin-report-message">{reportMessage}</p>}
                {selectedReport && (
                  <>
                    <p className="admin-report-summary">{selectedReport.summary}</p>
                    <div className="admin-report-snapshot">
                      <article>
                        <span>准备度</span>
                        <strong>{selectedReport.readiness_level || "待复盘"}</strong>
                        <p>{selectedReport.score_explanation || "暂无评分解释。"}</p>
                      </article>
                      <article>
                        <span>评分依据</span>
                        {(selectedReport.evidence || []).map((item) => <p key={item}>{item}</p>)}
                      </article>
                      <article>
                        <span>风险提醒</span>
                        {(selectedReport.risk_flags || []).map((item) => <p key={item}>{item}</p>)}
                      </article>
                    </div>
                    <div className="admin-report-dimensions">
                      {selectedReport.dimensions.map((dimension) => (
                        <article key={dimension.name}>
                          <span>{dimension.name}</span>
                          <b>{dimension.score}</b>
                          {dimension.level && <em>{dimension.level}</em>}
                          <p>{dimension.comment}</p>
                          {dimension.action && <p>{dimension.action}</p>}
                        </article>
                      ))}
                    </div>
                    <div className="admin-report-lists">
                      <section>
                        <h4>优势</h4>
                        {selectedReport.strengths.map((item) => <p key={item}>{item}</p>)}
                      </section>
                      <section>
                        <h4>改进</h4>
                        {selectedReport.improvements.map((item) => <p key={item}>{item}</p>)}
                      </section>
                      <section>
                        <h4>下一步</h4>
                        {selectedReport.next_plan.map((item) => <p key={item}>{item}</p>)}
                      </section>
                      <section>
                        <h4>优先动作</h4>
                        {(selectedReport.priority_actions || []).map((item) => <p key={item}>{item}</p>)}
                      </section>
                      <section>
                        <h4>推荐训练</h4>
                        {(selectedReport.recommended_drills || []).map((item) => <p key={item}>{item}</p>)}
                      </section>
                    </div>
                    <div className="admin-report-turns">
                      {selectedReport.turns.map((turn, index) => (
                        <details key={`${turn.round_name}-${index}`}>
                          <summary>{turn.round_name}{typeof turn.score === "number" ? ` · ${turn.score}` : ""}</summary>
                          <p><b>问：</b>{turn.question}</p>
                          <p><b>答：</b>{turn.answer || "用户未作答"}</p>
                          {turn.feedback && <p><b>反馈：</b>{turn.feedback}</p>}
                        </details>
                      ))}
                    </div>
                  </>
                )}
              </div>
            )}
            {selectedUserEmail && (
              <div className="admin-ops-grid">
                <form className="admin-ops-card" onSubmit={submitCustomerServiceNote}>
                  <div className="admin-ops-card-head">
                    <AppIcon icon="lucide:notebook-pen" size={22} />
                    <div>
                      <span className="eyebrow">Service Notes</span>
                      <h3>客服备注</h3>
                    </div>
                  </div>
                  <label>
                    备注类型
                    <input value={noteCategory} onChange={(event) => setNoteCategory(event.target.value)} placeholder="general / refund / complaint" />
                  </label>
                  <label>
                    关联训练 ID
                    <input value={noteSessionId} onChange={(event) => setNoteSessionId(event.target.value)} placeholder="可选，复制 session_id" />
                  </label>
                  <label>
                    沟通内容
                    <textarea
                      value={noteContent}
                      onChange={(event) => setNoteContent(event.target.value)}
                      placeholder="记录用户来源、沟通结论、补偿口径或后续跟进点"
                      rows={5}
                    />
                  </label>
                  <button type="submit" className="admin-primary-button">保存备注</button>
                  <div className="admin-note-list">
                    {customerServiceNotes.length === 0 && <p className="admin-empty-text">暂无客服备注。</p>}
                    {customerServiceNotes.slice(0, 5).map((note) => (
                      <article key={note.id}>
                        <strong>{note.category}</strong>
                        <p>{note.content}</p>
                        <span>{note.admin_email} · {formatDateTime(note.created_at)}{note.related_session_id ? ` · ${note.related_session_id}` : ""}</span>
                      </article>
                    ))}
                  </div>
                </form>

                <form className="admin-ops-card" onSubmit={submitRefundCase}>
                  <div className="admin-ops-card-head">
                    <AppIcon icon="lucide:receipt-text" size={22} />
                    <div>
                      <span className="eyebrow">Refund Cases</span>
                      <h3>退款纠纷</h3>
                    </div>
                  </div>
                  <div className="admin-form-grid">
                    <label>
                      原因
                      <input value={refundReason} onChange={(event) => setRefundReason(event.target.value)} placeholder="refund_request / service_dispute" />
                    </label>
                    <label>
                      退款金额
                      <input value={refundAmountYuan} onChange={(event) => setRefundAmountYuan(event.target.value)} placeholder="例如 19.90，可空" inputMode="decimal" />
                    </label>
                    <label>
                      次数补偿
                      <input value={refundCreditAdjustment} onChange={(event) => setRefundCreditAdjustment(event.target.value)} placeholder="例如 1，可空" inputMode="numeric" />
                    </label>
                    <label>
                      关联训练 ID
                      <input value={refundSessionId} onChange={(event) => setRefundSessionId(event.target.value)} placeholder="可选" />
                    </label>
                  </div>
                  <label>
                    纠纷描述
                    <textarea
                      value={refundDescription}
                      onChange={(event) => setRefundDescription(event.target.value)}
                      placeholder="记录用户诉求、核对依据、处理口径和下一步动作"
                      rows={5}
                    />
                  </label>
                  <button type="submit" className="admin-primary-button">创建纠纷记录</button>
                  <div className="admin-note-list">
                    {refundCases.length === 0 && <p className="admin-empty-text">暂无退款纠纷记录。</p>}
                    {refundCases.slice(0, 5).map((refundCase) => (
                      <article key={refundCase.id}>
                        <div className="admin-note-row">
                          <strong>{refundCase.reason}</strong>
                          <em>{refundCase.status}</em>
                        </div>
                        <p>{refundCase.description}</p>
                        <span>
                          {formatCents(refundCase.amount_cents, refundCase.currency)}
                          {refundCase.credit_adjustment != null ? ` · 次数 ${refundCase.credit_adjustment}` : ""}
                          {refundCase.related_session_id ? ` · ${refundCase.related_session_id}` : ""}
                        </span>
                        <div className="provider-row-actions">
                          <button type="button" onClick={() => void updateRefundCaseStatus(refundCase, "processing")}>处理中</button>
                          <button type="button" onClick={() => void updateRefundCaseStatus(refundCase, "resolved")}>已解决</button>
                          <button type="button" onClick={() => void updateRefundCaseStatus(refundCase, "rejected")}>驳回</button>
                        </div>
                      </article>
                    ))}
                  </div>
                </form>
              </div>
            )}
          </section>

          <section className="admin-provider-table admin-section-view admin-section-view--ai" id="admin-ai">
            <div className="admin-section-heading">
              <span className="eyebrow">Model Router</span>
              <h2>AI 服务状态</h2>
            </div>
            <div className="admin-provider-list">
              {providers.map((provider) => (
                <article className="provider-row" key={provider.id}>
                  <div>
                    <strong>{provider.id}</strong>
                    <span>{provider.provider_name} / {provider.model_name}</span>
                  </div>
                  <em>{provider.provider_type} · {provider.purpose} · {provider.region} · priority {provider.priority}</em>
                  <div className="provider-row-actions">
                    <button type="button" onClick={() => void testProvider(provider)}>
                      测试
                    </button>
                    <button type="button" className={provider.enabled ? "is-enabled" : ""} onClick={() => toggleProvider(provider)}>
                      {provider.enabled ? "启用中" : "已停用"}
                    </button>
                  </div>
                  <span>{provider.has_api_key ? `密钥：${provider.api_key_preview}` : "密钥：未配置"}{providerTestResults[provider.id] ? ` · ${providerTestResults[provider.id]}` : ""}</span>
                </article>
              ))}
            </div>
          </section>

          <section className="admin-provider-table admin-section-view admin-section-view--system" id="admin-system">
            <div className="admin-section-heading">
              <span className="eyebrow">System Config</span>
              <h2>系统参数</h2>
            </div>
            <div className="admin-config-list">
              {systemConfigs.map((config) => (
                <form
                  className="admin-config-row"
                  key={config.key}
                  onSubmit={(event) => {
                    event.preventDefault();
                    const formData = new FormData(event.currentTarget);
                    void updateSystemConfig(config, String(formData.get("value") ?? ""));
                  }}
                >
                  <label>
                    <span>{config.key}</span>
                    <em>{config.description}</em>
                  </label>
                  {typeof config.value === "boolean" ? (
                    <select name="value" defaultValue={String(config.value)}>
                      <option value="true">开启</option>
                      <option value="false">关闭</option>
                    </select>
                  ) : (
                    <input name="value" defaultValue={configInputValue(config.value)} />
                  )}
                  <button type="submit" className="admin-primary-button">保存</button>
                </form>
              ))}
            </div>
          </section>

          <section className="admin-provider-table admin-audit-table admin-section-view admin-section-view--audit" id="admin-audit">
            <div className="admin-section-heading">
              <span className="eyebrow">Audit Trail</span>
              <h2>操作审计日志</h2>
            </div>
            <div className="admin-provider-list">
              {auditLogs.length === 0 && <p className="admin-empty-text">暂无后台操作记录。</p>}
              {auditLogs.slice(0, 12).map((entry) => (
                <article className="provider-row" key={entry.id}>
                  <div>
                    <strong>{entry.action}</strong>
                    <span>{entry.admin_email} · {entry.target_type}</span>
                  </div>
                  <em>{entry.target_id}</em>
                  <span>{entry.created_at}</span>
                </article>
              ))}
            </div>
          </section>

          <section className="admin-provider-table admin-audit-table admin-section-view admin-section-view--audit">
            <div className="admin-section-heading">
              <span className="eyebrow">Account Trace</span>
              <h2>{selectedUserEmail ? `${selectedUserEmail} 的认证与售后记录` : "最近认证与售后记录"}</h2>
            </div>
            <div className="admin-trace-grid">
              <div className="admin-trace-list">
                <h3>登录日志</h3>
                {authLoginLogs.length === 0 && <p className="admin-empty-text">暂无登录日志。</p>}
                {authLoginLogs.slice(0, 8).map((entry) => (
                  <article key={entry.id}>
                    <div>
                      <strong>{entry.email}</strong>
                      <span>{entry.auth_method} · {entry.role} · {entry.ip_address ?? "unknown-ip"}</span>
                    </div>
                    <em className={entry.success ? "is-success" : "is-failed"}>
                      {entry.success ? "成功" : getApiErrorMessage({ detail: entry.failure_reason ?? undefined }, "失败")}
                    </em>
                    <small>{formatDateTime(entry.created_at)}</small>
                  </article>
                ))}
              </div>
              <div className="admin-trace-list">
                <h3>退款纠纷</h3>
                {refundCases.length === 0 && <p className="admin-empty-text">暂无退款纠纷。</p>}
                {refundCases.slice(0, 8).map((entry) => (
                  <article key={entry.id}>
                    <div>
                      <strong>{entry.user_email}</strong>
                      <span>{entry.reason} · {formatCents(entry.amount_cents, entry.currency)}</span>
                    </div>
                    <em className={entry.status === "resolved" ? "is-success" : "is-failed"}>{entry.status}</em>
                    <small>{formatDateTime(entry.updated_at)}</small>
                  </article>
                ))}
              </div>
            </div>
          </section>

          <section className="admin-provider-table admin-audit-table admin-section-view admin-section-view--audit">
            <div className="admin-section-heading">
              <span className="eyebrow">Content Safety</span>
              <h2>内容安全拦截记录</h2>
            </div>
            <div className="admin-provider-list">
              {contentSafetyLogs.length === 0 && <p className="admin-empty-text">暂无内容安全拦截记录。</p>}
              {contentSafetyLogs.slice(0, 12).map((entry) => (
                <article className="provider-row" key={entry.id}>
                  <div>
                    <strong>{entry.user_email ?? "unknown-user"} / {entry.source}</strong>
                    <span>
                      {entry.session_id ?? "no-session"} · {entry.categories.join(" / ")}
                      {entry.matched_terms.length > 0 ? ` · 命中 ${entry.matched_terms.slice(0, 3).join(" / ")}` : ""}
                    </span>
                    {entry.content_excerpt && <span>{entry.content_excerpt}</span>}
                  </div>
                  <em className={entry.risk_level === "high" ? "is-failed" : "is-warning"}>
                    {entry.action} / {entry.risk_level}
                  </em>
                  <span>{formatDateTime(entry.created_at)}</span>
                </article>
              ))}
            </div>
          </section>

          <section className="admin-provider-table admin-audit-table admin-section-view admin-section-view--ai">
            <div className="admin-section-heading">
              <span className="eyebrow">AI Call Logs</span>
              <h2>模型调用记录</h2>
            </div>
            <div className="admin-provider-list">
              {aiCallLogs.length === 0 && <p className="admin-empty-text">暂无模型调用记录。</p>}
              {aiCallLogs.slice(0, 12).map((entry) => (
                <article className="provider-row" key={entry.id}>
                  <div>
                    <strong>{entry.provider_name} / {entry.model_name}</strong>
                    <span>
                      {entry.session_id ?? "no-session"} · {entry.purpose}
                      {entry.provider_request_id ? ` · ${entry.provider_request_id}` : ""}
                    </span>
                  </div>
                  <em className={entry.success ? "is-success" : "is-failed"}>
                    {entry.success ? "成功" : getApiErrorMessage({ detail: entry.error_message ?? undefined }, "调用失败")}
                  </em>
                  <span>
                    {entry.latency_ms != null ? `${entry.latency_ms}ms` : "no-latency"}
                    {entry.input_tokens != null || entry.output_tokens != null
                      ? ` · ${entry.input_tokens ?? 0}/${entry.output_tokens ?? 0} tokens`
                      : ""}
                    {entry.audio_duration_ms != null ? ` · ${Math.round(entry.audio_duration_ms / 1000)}s audio` : ""}
                    {entry.characters != null ? ` · ${entry.characters} chars` : ""}
                    {entry.estimated_cost_cents != null ? ` · ${formatCents(entry.estimated_cost_cents)}` : ""}
                    {" · "}
                    {formatDateTime(entry.created_at)}
                  </span>
                </article>
              ))}
            </div>
          </section>
        </section>
      </div>
    </main>
  );
}
