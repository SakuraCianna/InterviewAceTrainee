import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { AppIcon } from "../../components/AppIcon";
import { CSRF_COOKIE_NAME, getApiErrorMessage, getCookie } from "../../lib/api";
import { useEmailCodeCooldown } from "../../hooks/useEmailCodeCooldown";
import { normalizeEmail, retryAfterSeconds } from "../../lib/emailCooldown";
import { AdminLoginView } from "./AdminLoginView";
import { AdminSidebar } from "./AdminSidebar";
import {
  adjustUserCredits,
  createCustomerServiceNote,
  createRefundCase,
  getCurrentUser,
  getDashboardStats,
  getInterviewCoreHealth,
  getUserInterviewReport,
  issueVouchers,
  listAiCallLogs,
  listAuditLogs,
  listAuthLoginLogs,
  listContentSafetyLogs,
  listCreditLedger,
  listCustomerServiceNotes,
  listProviders,
  listRefundCases,
  listSystemConfigs,
  listUserInterviews,
  loginAdmin,
  logoutAdmin,
  requestAdminEmailCode,
  searchAdminUsers,
  testProviderConfig,
  updateAdminUserRole,
  updateAdminUserStatus,
  updateProviderEnabled,
  updateRefundCase,
  updateSystemConfigValue,
} from "./adminApi";
import { AdminChart, barDashboardOption, donutDashboardOption, lineDashboardOption } from "./dashboardCharts";
import { configInputValue, parseConfigInput, parseOptionalAmountCents, parseOptionalInteger } from "./formUtils";
import type {
  AICallLogEntry,
  AdminAuditLog,
  AdminDashboardStats,
  AdminInterviewHistoryItem,
  AdminInterviewReport,
  AdminSectionKey,
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
} from "./types";

const ADMIN_CODE_COOLDOWN_SECONDS = 90;
const ADMIN_CODE_STORAGE_PREFIX = "mianba_admin_code_next:";

const interviewTypeLabels: Record<string, string> = {
  job: "求职",
  postgraduate: "考研",
  civil_service: "公考",
  ielts: "雅思",
};

const adminSectionNavItems: { key: AdminSectionKey; label: string; icon: string }[] = [
  { key: "overview", label: "概览", icon: "lucide:gauge" },
  { key: "credits", label: "次数", icon: "lucide:coins" },
  { key: "users", label: "用户", icon: "lucide:users" },
  { key: "ai", label: "AI 服务", icon: "lucide:bot" },
  { key: "system", label: "系统", icon: "lucide:settings-2" },
  { key: "audit", label: "审计", icon: "lucide:shield-check" },
];

const adminSectionMeta: Record<AdminSectionKey, { title: string; description: string; icon: string }> = {
  overview: {
    title: "运营控制台",
    description: "集中查看用户、训练、报告、面试核心和服务稳定性。",
    icon: "lucide:layout-dashboard",
  },
  credits: {
    title: "次数与体验券",
    description: "处理人工开通、补偿、体验券发放和售后追溯。",
    icon: "lucide:badge-dollar-sign",
  },
  users: {
    title: "用户运营",
    description: "检索用户、复核训练记录、处理客服备注和退款纠纷。",
    icon: "lucide:user-round-search",
  },
  ai: {
    title: "AI 服务",
    description: "检查模型路由、供应商状态和最近的模型调用质量。",
    icon: "lucide:bot-message-square",
  },
  system: {
    title: "系统参数",
    description: "维护后台可配置项，保存后会进入审计日志。",
    icon: "lucide:sliders-horizontal",
  },
  audit: {
    title: "审计与风控",
    description: "追踪管理员操作、登录、退款、内容安全和调用异常。",
    icon: "lucide:shield-check",
  },
};

const creditReasonOptions = [
  { value: "manual_grant", label: "人工开通", help: "用户购买或人工确认后增加次数。" },
  { value: "trial_bonus", label: "试用赠送", help: "新用户、活动或人工试用赠送。" },
  { value: "service_compensation", label: "服务补偿", help: "验证码、模型、语音等系统问题导致体验受损后补偿。" },
  { value: "refund_adjustment", label: "退款退回", help: "售后退款、纠纷处理或次数退回。" },
  { value: "manual_correction", label: "余额修正", help: "管理员核对账本后修正异常余额。" },
  { value: "manual_deduction", label: "人工扣减", help: "误发、滥用或人工确认后扣减次数。" },
];

const voucherReasonOptions = [
  { value: "manual_voucher_grant", label: "人工发放体验券", help: "用于内测、客服补偿或人工确认后的体验券发放。" },
  { value: "launch_bonus", label: "上线活动赠送", help: "用于首批内测、上线活动或推广批次。" },
  { value: "service_compensation", label: "服务补偿", help: "因服务异常、沟通承诺或售后处理补发体验券。" },
  { value: "beta_user_reward", label: "内测用户奖励", help: "感谢内测用户反馈或配合验证。" },
];

const noteCategoryOptions = [
  { value: "general", label: "常规备注" },
  { value: "refund_request", label: "退款沟通" },
  { value: "service_dispute", label: "服务争议" },
  { value: "service_compensation", label: "补偿记录" },
];

const refundReasonOptions = [
  { value: "refund_request", label: "退款申请" },
  { value: "service_dispute", label: "服务争议" },
  { value: "service_compensation", label: "服务补偿" },
  { value: "manual_correction", label: "人工修正" },
];

const businessCodeLabels: Record<string, string> = {
  manual_grant: "人工开通",
  trial_bonus: "试用赠送",
  service_compensation: "服务补偿",
  refund_adjustment: "退款退回",
  manual_correction: "余额修正",
  manual_deduction: "人工扣减",
  manual_adjustment: "人工调整",
  admin_manual_adjustment: "管理员人工调整",
  manual_voucher_grant: "人工发放体验券",
  launch_bonus: "上线活动赠送",
  beta_user_reward: "内测用户奖励",
  refund_request: "退款申请",
  service_dispute: "服务争议",
  general: "常规备注",
  processing: "处理中",
  resolved: "已解决",
  rejected: "已驳回",
  open: "待处理",
  allowed: "已放行",
  blocked: "已拦截",
  high: "高风险",
  medium: "中风险",
  low: "低风险",
  password: "密码登录",
  email_code: "邮箱验证码",
  admin: "管理员",
  user: "普通用户",
  completed: "已完成",
  created: "已创建",
  in_progress: "进行中",
  failed: "失败",
  canceled: "已取消",
  credit_adjust: "次数调整",
  voucher_issue: "体验券发放",
  user_status_update: "用户状态变更",
  user_role_update: "用户角色变更",
  customer_service_note_create: "客服备注创建",
  refund_case_create: "退款纠纷创建",
  refund_case_update: "退款纠纷更新",
  system_config_update: "系统参数更新",
  provider_config_update: "供应商配置更新",
  user_credit: "用户次数",
  interview_voucher: "体验券",
  customer_service_note: "客服备注",
  refund_case: "退款纠纷",
  system_config: "系统参数",
  provider_config: "供应商配置",
  llm: "大模型",
  asr: "语音识别",
  tts: "语音合成",
  embedding: "向量模型",
  chat: "对话生成",
  report: "报告生成",
  feedback: "面试反馈",
  interview_question: "面试提问",
  interview_feedback: "面试反馈",
  report_generation: "报告生成",
};

function adminSectionFromHash(hash: string): AdminSectionKey | null {
  const key = hash.replace(/^#admin-/, "") as AdminSectionKey;
  return adminSectionNavItems.some((item) => item.key === key) ? key : null;
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
  const { cooldownSeconds: adminCodeCooldownSeconds, startCooldown: startAdminCodeCooldown } = useEmailCodeCooldown({
    email: loginEmail,
    storagePrefix: ADMIN_CODE_STORAGE_PREFIX,
    defaultSeconds: ADMIN_CODE_COOLDOWN_SECONDS,
  });
  const [creditUser, setCreditUser] = useState("");
  const [creditAmount, setCreditAmount] = useState("1");
  const [creditReason, setCreditReason] = useState("manual_grant");
  const [creditNote, setCreditNote] = useState("");
  const [voucherEmails, setVoucherEmails] = useState("");
  const [voucherQuantity, setVoucherQuantity] = useState("1");
  const [voucherAllUsers, setVoucherAllUsers] = useState(false);
  const [voucherReason, setVoucherReason] = useState("manual_voucher_grant");
  const [voucherNote, setVoucherNote] = useState("");
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
  const [interviewCoreHealth, setInterviewCoreHealth] = useState<InterviewCoreHealth | null>(null);
  const [interviewCoreHealthMessage, setInterviewCoreHealthMessage] = useState("面试核心健康检查尚未读取。");
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

  function formatCorePercent(value?: number | null) {
    if (value == null) {
      return "暂无";
    }
    const percent = Math.round(value * 1000) / 10;
    return `${Number.isInteger(percent) ? percent.toFixed(0) : percent.toFixed(1)}%`;
  }

  function formatCoreCount(value?: number | null) {
    return value == null ? "暂无" : value.toLocaleString("zh-CN");
  }

  function coreStatusLabel(ready: boolean) {
    return ready ? "正常" : "退化";
  }

  function coreStatusClass(ready: boolean) {
    return ready ? "admin-health-pill is-ready" : "admin-health-pill is-degraded";
  }

  function interviewTypeLabel(value: string) {
    return interviewTypeLabels[value] ?? value;
  }

  function businessLabel(value?: string | null) {
    if (!value) {
      return "未记录";
    }
    return businessCodeLabels[value] ?? value;
  }

  function formatLedgerReason(entry: CreditLedgerEntry) {
    const parts = [businessLabel(entry.reason), `余额 ${entry.balance_after}`];
    if (entry.note) {
      parts.push(businessLabel(entry.note));
    }
    return parts.join(" · ");
  }

  const selectedCreditReason = creditReasonOptions.find((option) => option.value === creditReason) ?? creditReasonOptions[0];
  const selectedVoucherReason = voucherReasonOptions.find((option) => option.value === voucherReason) ?? voucherReasonOptions[0];
  const activeSectionMeta = adminSectionMeta[activeAdminSection];
  const databaseStatusClass = dashboardStats?.database_ready ? "is-ready" : "is-muted";
  const databaseStatusLabel = !dashboardStats ? "数据待同步" : dashboardStats.database_ready ? "数据库在线" : "等待数据库迁移";
  const coreStatusBadgeClass = !interviewCoreHealth ? "is-muted" : interviewCoreHealth.ready ? "is-ready" : "is-danger";
  const coreStatusBadgeLabel = !interviewCoreHealth ? "核心待检查" : interviewCoreHealth.ready ? "核心正常" : "核心退化";

  const consoleLayoutRef = useRef<HTMLDivElement | null>(null);

  function selectAdminSection(section: AdminSectionKey) {
    setActiveAdminSection(section);
    window.history.replaceState(null, "", `#admin-${section}`);
    window.requestAnimationFrame(() => {
      consoleLayoutRef.current?.scrollTo({ top: 0 });
    });
  }

  async function loadCurrentUser() {
    setIsLoading(true);
    let result: Awaited<ReturnType<typeof getCurrentUser>>;
    try {
      result = await getCurrentUser();
    } catch {
      setCurrentUser(null);
      setIsLoading(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const { response, data: user } = result;
    if (!response.ok) {
      setCurrentUser(null);
      setIsLoading(false);
      setMessage("请使用管理员邮箱、密码和邮箱验证码进入后台。");
      return;
    }

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
      loadInterviewCoreHealth(),
      loadSystemConfigs(),
      loadAuditLogs(),
      loadAiCallLogs(),
      loadContentSafetyLogs(),
      loadAuthLoginLogs(),
      loadRefundCases(),
    ]);
  }

  async function loadProviders() {
    const { response, data } = await listProviders();
    if (!response.ok) {
      setMessage("AI 服务状态读取失败。");
      return;
    }
    setProviders(data);
  }

  async function loadAuditLogs() {
    const { response, data } = await listAuditLogs();
    if (!response.ok) {
      return;
    }
    setAuditLogs(data);
  }

  async function loadAiCallLogs() {
    const { response, data } = await listAiCallLogs();
    if (!response.ok) {
      return;
    }
    setAiCallLogs(data);
  }

  async function loadContentSafetyLogs() {
    const { response, data } = await listContentSafetyLogs();
    if (!response.ok) {
      return;
    }
    setContentSafetyLogs(data);
  }

  async function loadAuthLoginLogs(userEmail?: string) {
    const { response, data } = await listAuthLoginLogs(userEmail);
    if (!response.ok) {
      return;
    }
    setAuthLoginLogs(data);
  }

  async function loadCustomerServiceNotes(userEmail?: string) {
    const normalizedEmail = userEmail?.trim();
    if (!normalizedEmail) {
      setCustomerServiceNotes([]);
      return;
    }
    const { response, data } = await listCustomerServiceNotes(normalizedEmail);
    if (!response.ok) {
      return;
    }
    setCustomerServiceNotes(data);
  }

  async function loadRefundCases(userEmail?: string) {
    const { response, data } = await listRefundCases(userEmail);
    if (!response.ok) {
      return;
    }
    setRefundCases(data);
  }

  async function loadSystemConfigs() {
    const { response, data } = await listSystemConfigs();
    if (!response.ok) {
      return;
    }
    setSystemConfigs(data);
  }

  async function loadDashboardStats() {
    const { response, data } = await getDashboardStats();
    if (!response.ok) {
      return;
    }
    setDashboardStats(data);
  }

  async function loadInterviewCoreHealth() {
    let result: Awaited<ReturnType<typeof getInterviewCoreHealth>>;
    try {
      result = await getInterviewCoreHealth();
    } catch {
      setInterviewCoreHealth(null);
      setInterviewCoreHealthMessage("面试核心健康检查读取失败，请确认后端服务和 /api/health/interview-core 可访问。");
      return;
    }

    const { response, data } = result;
    if (!response.ok) {
      setInterviewCoreHealth(null);
      setInterviewCoreHealthMessage(`面试核心健康检查读取失败：${getApiErrorMessage(data, "请确认后端健康检查接口可访问。")}`);
      return;
    }
    setInterviewCoreHealth(data);
    setInterviewCoreHealthMessage(data.ready ? "面试核心链路正常。" : data.failure_summary || "面试核心链路存在退化。");
  }

  async function loadCreditLedger(userEmail = creditUser) {
    if (!userEmail) {
      setCreditLedger([]);
      return;
    }

    const { response, data } = await listCreditLedger(userEmail);
    if (!response.ok) {
      return;
    }
    setCreditLedger(data);
  }

  async function searchUsers(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    const query = userSearchQuery.trim();
    if (!query) {
      setMessage("请先输入用户邮箱或邮箱关键词。");
      return;
    }

    const { response, data: users } = await searchAdminUsers(query);
    if (!response.ok) {
      setMessage("用户搜索失败，请确认管理员会话仍然有效。");
      return;
    }

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
    const { response, data } = await listUserInterviews(userEmail);
    if (!response.ok) {
      setSelectedUserHistory([]);
      setMessage("用户训练记录读取失败。");
      return;
    }
    setSelectedUserHistory(data);
  }

  async function loadInterviewReport(userEmail: string, sessionId: string) {
    setSelectedReport(null);
    setReportMessage("正在读取报告。");
    const { response, data } = await getUserInterviewReport(userEmail, sessionId);
    if (!response.ok) {
      setReportMessage("该训练尚未生成报告，或当前管理员会话已失效。");
      return;
    }

    setSelectedReport(data);
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
    let result: Awaited<ReturnType<typeof requestAdminEmailCode>>;
    try {
      result = await requestAdminEmailCode(normalizeEmail(loginEmail));
    } catch {
      setIsRequestingAdminCode(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const { response, data } = result;
    setIsRequestingAdminCode(false);
    if (!response.ok) {
      if (response.status === 429) {
        const retryAfter = retryAfterSeconds(response, ADMIN_CODE_COOLDOWN_SECONDS);
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
    let result: Awaited<ReturnType<typeof loginAdmin>>;
    try {
      result = await loginAdmin(loginEmail, loginPassword, loginCode);
    } catch {
      setIsSubmittingAdminLogin(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const { response, data } = result;
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

    const { response, data } = await adjustUserCredits(creditUser, {
      change_amount: amount,
      reason,
      note: note || undefined,
    });
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

  async function submitVoucherIssue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const quantity = Number.parseInt(voucherQuantity, 10);
    const userEmails = voucherEmails
      .split(/[\s,;]+/)
      .map((email) => email.trim())
      .filter(Boolean);
    if (!voucherAllUsers && userEmails.length === 0) {
      setMessage("请填写至少一个用户邮箱，或选择发放给全部普通用户。");
      return;
    }
    if (!Number.isFinite(quantity) || quantity <= 0) {
      setMessage("体验券数量需要填写为正整数。");
      return;
    }

    const { response, data } = await issueVouchers({
      user_emails: voucherAllUsers ? [] : userEmails,
      issue_all_active_users: voucherAllUsers,
      quantity,
      voucher_type: "admin_grant",
      reason: voucherReason.trim() || "manual_voucher_grant",
      note: voucherNote.trim() || undefined,
    });
    if (!response.ok) {
      setMessage(`体验券发放失败：${getApiErrorMessage(data, "请检查用户邮箱和发放数量。")}`);
      return;
    }

    setMessage(`已发放 ${data.total_vouchers} 张体验券，覆盖 ${data.total_recipients} 个用户。`);
    setVoucherEmails("");
    setVoucherQuantity("1");
    setVoucherAllUsers(false);
    setVoucherReason("manual_voucher_grant");
    setVoucherNote("");
    await Promise.all([loadDashboardStats(), loadAuditLogs()]);
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

    const { response, data } = await createCustomerServiceNote(selectedUserEmail, {
      category,
      content,
      related_session_id: noteSessionId.trim() || undefined,
    });
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

    const { response, data } = await createRefundCase(selectedUserEmail, {
      reason,
      description,
      amount_cents: amountCents,
      currency: "CNY",
      credit_adjustment: creditAdjustment,
      related_session_id: refundSessionId.trim() || undefined,
    });
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
    const { response, data } = await updateRefundCase(refundCase.id, {
      status: statusValue,
      resolution: statusValue === "resolved" ? refundCase.resolution || "已人工处理完成" : refundCase.resolution,
    });
    if (!response.ok) {
      setMessage(`退款纠纷状态更新失败：${getApiErrorMessage(data, "请稍后重试。")}`);
      return;
    }

    setMessage(`退款纠纷 ${refundCase.id} 已更新为 ${statusValue}。`);
    await Promise.all([loadDashboardStats(), loadRefundCases(selectedUserEmail || undefined), loadAuditLogs()]);
  }

  async function toggleProvider(provider: ProviderConfig) {
    const { response } = await updateProviderEnabled(provider.id, !provider.enabled);
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
    const { response, data } = await testProviderConfig(provider.id);
    const resultDetail = getApiErrorMessage(data, "服务测试未返回明确结果。");
    const resultText = response.ok
      ? `${data.success ? "通过" : "未通过"}：${resultDetail}`
      : `测试失败：${resultDetail}`;
    setProviderTestResults((previous) => ({ ...previous, [provider.id]: resultText }));
    await loadAuditLogs();
  }

  async function updateUserStatus(user: AdminUserSearchItem, isActive: boolean) {
    const { response, data } = await updateAdminUserStatus(user.email, isActive);
    if (!response.ok) {
      setMessage(`用户状态更新失败：${getApiErrorMessage(data, "请稍后重试。")}`);
      return;
    }
    setMessage(`${user.email} 已${isActive ? "启用" : "禁用"}。`);
    await Promise.all([loadDashboardStats(), searchUsers(), loadAuditLogs()]);
  }

  async function updateUserRole(user: AdminUserSearchItem, role: "user" | "admin") {
    const { response, data } = await updateAdminUserRole(user.email, role);
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

  async function updateSystemConfig(config: SystemConfig, rawValue: string) {
    let value: SystemConfig["value"];
    try {
      value = parseConfigInput(config.value, rawValue);
    } catch {
      setMessage(`${config.key} 需要合法 JSON。`);
      return;
    }
    const { response, data } = await updateSystemConfigValue(config.key, value);
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
      loadInterviewCoreHealth(),
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
    await logoutAdmin();
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
    setInterviewCoreHealth(null);
    setInterviewCoreHealthMessage("面试核心健康检查尚未读取。");
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
      <AdminLoginView
        isLoading={isLoading}
        isRequestingCode={isRequestingAdminCode}
        isSubmitting={isSubmittingAdminLogin}
        codeCooldownSeconds={adminCodeCooldownSeconds}
        email={loginEmail}
        password={loginPassword}
        code={loginCode}
        message={message}
        onEmailChange={setLoginEmail}
        onPasswordChange={setLoginPassword}
        onCodeChange={setLoginCode}
        onRequestCode={requestAdminCode}
        onSubmit={submitAdminLogin}
      />
    );
  }

  const adminStatusMessage = message.startsWith("已进入后台") ? "后台在线，操作会记录到审计日志" : message;

  return (
    <main className="workspace-page admin-page admin-page--authed" data-admin-section={activeAdminSection}>
      <AdminSidebar
        activeSection={activeAdminSection}
        currentUser={currentUser}
        navItems={adminSectionNavItems}
        statusMessage={adminStatusMessage}
        onSelectSection={selectAdminSection}
        onRefresh={() => void refreshAdminData()}
        onLogout={() => void logout()}
      />
      <div className="admin-console-layout" ref={consoleLayoutRef}>
        <header className="admin-console-topbar">
          <div className="admin-console-title">
            <span className="admin-console-title-icon">
              <AppIcon icon={activeSectionMeta.icon} size={24} />
            </span>
            <div>
              <p>管理员工作台</p>
              <h1>{activeSectionMeta.title}</h1>
              <span>{activeSectionMeta.description}</span>
            </div>
          </div>
          <div className="admin-console-commandbar">
            <span className={`admin-console-status ${databaseStatusClass}`}>
              <AppIcon icon={dashboardStats?.database_ready ? "lucide:database-zap" : "lucide:database"} size={17} />
              {databaseStatusLabel}
            </span>
            <span className={`admin-console-status ${coreStatusBadgeClass}`}>
              <AppIcon icon={interviewCoreHealth?.ready ? "lucide:shield-check" : "lucide:shield-alert"} size={17} />
              {coreStatusBadgeLabel}
            </span>
            <button type="button" onClick={() => void refreshAdminData()}>
              <AppIcon icon="lucide:refresh-cw" size={18} />
              刷新
            </button>
          </div>
        </header>
        <section className="admin-console-main">
          {dashboardStats && dashboardChartOptions && (
            <section className="admin-dashboard admin-section-view admin-section-view--overview" id="admin-overview">
              <div className="admin-section-heading admin-dashboard-heading">
                <div>
                  <span className="eyebrow">数据看板</span>
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

              <section className="admin-core-health">
                <div className="admin-core-health-heading">
                  <div>
                    <h3>面试核心观测</h3>
                    <p>直接读取后端 interview-core 健康检查，用来定位能力卡片、向量覆盖和召回探针是哪一层退化。</p>
                  </div>
                  <span className={!interviewCoreHealth ? "admin-dashboard-badge is-muted" : interviewCoreHealth.ready ? "admin-dashboard-badge" : "admin-dashboard-badge is-danger"}>
                    {!interviewCoreHealth ? "等待健康检查" : interviewCoreHealth.ready ? "核心正常" : "核心退化"}
                  </span>
                </div>

                {!interviewCoreHealth ? (
                  <p className="admin-empty-text">{interviewCoreHealthMessage}</p>
                ) : (
                  <>
                    <div className="admin-core-health-grid">
                      <article className={interviewCoreHealth.capability_cards.ready ? "admin-core-card" : "admin-core-card is-degraded"}>
                        <div className="admin-core-card-head">
                          <span>能力卡片 seed</span>
                          <em className={coreStatusClass(interviewCoreHealth.capability_cards.ready)}>
                            {coreStatusLabel(interviewCoreHealth.capability_cards.ready)}
                          </em>
                        </div>
                        <strong>{formatCoreCount(interviewCoreHealth.capability_cards.total_seed_count)}</strong>
                        <p className="admin-core-source">
                          <span>{interviewCoreHealth.capability_cards.source_version ? `版本 ${interviewCoreHealth.capability_cards.source_version}` : "未记录版本"}</span>
                          {interviewCoreHealth.capability_cards.source_policy && <span>{interviewCoreHealth.capability_cards.source_policy}</span>}
                        </p>
                        <div className="admin-core-breakdown">
                          {Object.entries(interviewCoreHealth.capability_cards.counts_by_interview_type).map(([type, count]) => {
                            const minimum = interviewCoreHealth.capability_cards.expected_minimums[type];
                            return (
                              <span key={type}>
                                {interviewTypeLabel(type)}
                                <b>{formatCoreCount(count)}{minimum != null ? `/${formatCoreCount(minimum)}` : ""}</b>
                              </span>
                            );
                          })}
                        </div>
                        {(interviewCoreHealth.capability_cards.error ||
                          interviewCoreHealth.capability_cards.missing_preset_files.length > 0 ||
                          interviewCoreHealth.capability_cards.duplicate_seed_ids.length > 0) && (
                          <div className="admin-core-alerts">
                            {interviewCoreHealth.capability_cards.error && <p>{interviewCoreHealth.capability_cards.error}</p>}
                            {interviewCoreHealth.capability_cards.missing_preset_files.length > 0 && (
                              <p>缺失文件 {interviewCoreHealth.capability_cards.missing_preset_files.slice(0, 3).join(" / ")}</p>
                            )}
                            {interviewCoreHealth.capability_cards.duplicate_seed_ids.length > 0 && (
                              <p>重复 ID {interviewCoreHealth.capability_cards.duplicate_seed_ids.slice(0, 5).join(" / ")}</p>
                            )}
                          </div>
                        )}
                      </article>

                      <article className={interviewCoreHealth.capability_vectors.ready ? "admin-core-card" : "admin-core-card is-degraded"}>
                        <div className="admin-core-card-head">
                          <span>能力向量覆盖</span>
                          <em className={coreStatusClass(interviewCoreHealth.capability_vectors.ready)}>
                            {coreStatusLabel(interviewCoreHealth.capability_vectors.ready)}
                          </em>
                        </div>
                        <strong>{formatCorePercent(interviewCoreHealth.capability_vectors.coverage_rate)}</strong>
                        <p>
                          覆盖 {formatCoreCount(interviewCoreHealth.capability_vectors.distinct_seed_count ?? interviewCoreHealth.capability_vectors.total_vector_count)}
                          /{formatCoreCount(interviewCoreHealth.capability_vectors.expected_seed_count)}
                          {" · "}
                          非空向量 {formatCoreCount(interviewCoreHealth.capability_vectors.non_empty_vector_count)}
                          /{formatCoreCount(interviewCoreHealth.capability_vectors.total_vector_count)}
                        </p>
                        <div className="admin-core-metrics">
                          <span>表</span>
                          <b>{interviewCoreHealth.capability_vectors.table_exists ? interviewCoreHealth.capability_vectors.table_name : "未创建"}</b>
                          <span>观测列</span>
                          <b>
                            {interviewCoreHealth.capability_vectors.missing_observation_columns.length > 0
                              ? `缺 ${interviewCoreHealth.capability_vectors.missing_observation_columns.join(" / ")}`
                              : "完整"}
                          </b>
                          <span>模型</span>
                          <b>
                            {interviewCoreHealth.capability_vectors.embedding_models.length > 0
                              ? interviewCoreHealth.capability_vectors.embedding_models.map((item) => `${item.model}:${item.count}`).join(" / ")
                              : "未记录"}
                          </b>
                          <span>状态</span>
                          <b>
                            {interviewCoreHealth.capability_vectors.status_counts.length > 0
                              ? interviewCoreHealth.capability_vectors.status_counts.map((item) => `${item.status}:${item.count}`).join(" / ")
                              : "未分状态"}
                          </b>
                        </div>
                        {interviewCoreHealth.capability_vectors.detail && (
                          <div className="admin-core-alerts">
                            <p>{interviewCoreHealth.capability_vectors.detail}</p>
                          </div>
                        )}
                      </article>

                      <article className={interviewCoreHealth.recall_quality.ready ? "admin-core-card" : "admin-core-card is-degraded"}>
                        <div className="admin-core-card-head">
                          <span>召回探针</span>
                          <em className={coreStatusClass(interviewCoreHealth.recall_quality.ready)}>
                            {coreStatusLabel(interviewCoreHealth.recall_quality.ready)}
                          </em>
                        </div>
                        <strong>{interviewCoreHealth.recall_quality.passed_probe_count}/{interviewCoreHealth.recall_quality.probe_count}</strong>
                        <p>四类面试的固定探针命中预期能力卡片。</p>
                        <div className="admin-core-probes">
                          {interviewCoreHealth.recall_quality.probes.map((probe) => (
                            <article key={probe.name} className={probe.ready ? "is-ready" : "is-degraded"}>
                              <div>
                                <strong>{probe.name}</strong>
                                <span>{interviewTypeLabel(probe.interview_type)} · {probe.matched_title ?? probe.matched_preset_id ?? "未命中"}</span>
                              </div>
                              <em>{probe.ready ? "通过" : "失败"}</em>
                              <small>Top {probe.top_score} / Gap {probe.top_score_gap ?? "暂无"} / {probe.match_count} 条</small>
                            </article>
                          ))}
                        </div>
                      </article>
                    </div>

                    {interviewCoreHealth.failure_reasons.length > 0 ? (
                      <div className="admin-core-failures">
                        <strong>退化原因</strong>
                        {interviewCoreHealth.failure_reasons.map((reason) => (
                          <p key={reason}>{reason}</p>
                        ))}
                      </div>
                    ) : (
                      <p className="admin-core-ok">{interviewCoreHealth.failure_summary}</p>
                    )}
                  </>
                )}
              </section>

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
                      <span className="eyebrow">用户排行</span>
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
                    <span>{formatLedgerReason(entry)}</span>
                  </div>
                ))}
              </div>
            </form>
            <form className="admin-panel" onSubmit={submitVoucherIssue}>
              <h2>发放体验券</h2>
              <label className="admin-checkbox-line">
                <input
                  type="checkbox"
                  checked={voucherAllUsers}
                  onChange={(event) => setVoucherAllUsers(event.target.checked)}
                />
                发放给全部启用中的普通用户
              </label>
              <label>
                用户邮箱
                <textarea
                  value={voucherEmails}
                  onChange={(event) => setVoucherEmails(event.target.value)}
                  placeholder="多个邮箱可用换行、空格或逗号分隔"
                  disabled={voucherAllUsers}
                  rows={4}
                />
              </label>
              <label>
                每人发放张数
                <input type="number" min={1} max={20} value={voucherQuantity} onChange={(event) => setVoucherQuantity(event.target.value)} required />
              </label>
              <label>
                发放原因
                <select value={voucherReason} onChange={(event) => setVoucherReason(event.target.value)} required>
                  {voucherReasonOptions.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
                <small className="admin-field-help">{selectedVoucherReason.help} 体验券优先抵扣下一场模拟面试，不改变用户的训练次数余额。</small>
              </label>
              <label>
                处理备注
                <input value={voucherNote} onChange={(event) => setVoucherNote(event.target.value)} placeholder="例如：首批内测用户体验券" maxLength={240} />
              </label>
              <div className="admin-action-row">
                <button type="submit" className="admin-primary-button">发放体验券</button>
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
              <span className="eyebrow">用户运营</span>
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
                        <em>{businessLabel(user.role)} / {user.is_active ? "启用中" : "已禁用"} / 余额 {user.credit_balance} 次</em>
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
                        <strong>{interviewTypeLabel(item.interview_type)}</strong>
                        <span>{businessLabel(item.status)} / 第 {item.current_step_index + 1} 步，共 {item.total_steps} 步</span>
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
                    <span className="eyebrow">报告复核</span>
                    <h3>{selectedReport ? `${selectedReport.user_email} · ${interviewTypeLabel(selectedReport.interview_type)}` : "报告复核"}</h3>
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
                      <span className="eyebrow">客服备注</span>
                      <h3>客服备注</h3>
                    </div>
                  </div>
                  <label>
                    备注类型
                    <select value={noteCategory} onChange={(event) => setNoteCategory(event.target.value)}>
                      {noteCategoryOptions.map((option) => (
                        <option key={option.value} value={option.value}>{option.label}</option>
                      ))}
                    </select>
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
                        <strong>{businessLabel(note.category)}</strong>
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
                      <span className="eyebrow">退款纠纷</span>
                      <h3>退款纠纷</h3>
                    </div>
                  </div>
                  <div className="admin-form-grid">
                    <label>
                      原因
                      <select value={refundReason} onChange={(event) => setRefundReason(event.target.value)}>
                        {refundReasonOptions.map((option) => (
                          <option key={option.value} value={option.value}>{option.label}</option>
                        ))}
                      </select>
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
                          <strong>{businessLabel(refundCase.reason)}</strong>
                          <em>{businessLabel(refundCase.status)}</em>
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
              <span className="eyebrow">模型路由</span>
              <h2>AI 服务状态</h2>
            </div>
            <div className="admin-provider-list">
              {providers.map((provider) => (
                <article className="provider-row" key={provider.id}>
                  <div>
                    <strong>{provider.id}</strong>
                    <span>{provider.provider_name} / {provider.model_name}</span>
                  </div>
                  <em>{businessLabel(provider.provider_type)} · {businessLabel(provider.purpose)} · {provider.region} · 优先级 {provider.priority}</em>
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
              <span className="eyebrow">系统参数</span>
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
              <span className="eyebrow">操作审计</span>
              <h2>操作审计日志</h2>
            </div>
            <div className="admin-provider-list">
              {auditLogs.length === 0 && <p className="admin-empty-text">暂无后台操作记录。</p>}
              {auditLogs.slice(0, 12).map((entry) => (
                <article className="provider-row" key={entry.id}>
                  <div>
                    <strong>{businessLabel(entry.action)}</strong>
                    <span>{entry.admin_email} · {businessLabel(entry.target_type)}</span>
                  </div>
                  <em>{entry.target_id}</em>
                  <span>{entry.created_at}</span>
                </article>
              ))}
            </div>
          </section>

          <section className="admin-provider-table admin-audit-table admin-section-view admin-section-view--audit">
            <div className="admin-section-heading">
              <span className="eyebrow">账户追踪</span>
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
                      <span>{businessLabel(entry.auth_method)} · {businessLabel(entry.role)} · {entry.ip_address ?? "未知 IP"}</span>
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
                      <span>{businessLabel(entry.reason)} · {formatCents(entry.amount_cents, entry.currency)}</span>
                    </div>
                    <em className={entry.status === "resolved" ? "is-success" : "is-failed"}>{businessLabel(entry.status)}</em>
                    <small>{formatDateTime(entry.updated_at)}</small>
                  </article>
                ))}
              </div>
            </div>
          </section>

          <section className="admin-provider-table admin-audit-table admin-section-view admin-section-view--audit">
            <div className="admin-section-heading">
              <span className="eyebrow">内容安全</span>
              <h2>内容安全拦截记录</h2>
            </div>
            <div className="admin-provider-list">
              {contentSafetyLogs.length === 0 && <p className="admin-empty-text">暂无内容安全拦截记录。</p>}
              {contentSafetyLogs.slice(0, 12).map((entry) => (
                <article className="provider-row" key={entry.id}>
                  <div>
                    <strong>{entry.user_email ?? "未知用户"} / {entry.source}</strong>
                    <span>
                      {entry.session_id ?? "无会话"} · {entry.categories.map((item) => businessLabel(item)).join(" / ")}
                      {entry.matched_terms.length > 0 ? ` · 命中 ${entry.matched_terms.slice(0, 3).join(" / ")}` : ""}
                    </span>
                    {entry.content_excerpt && <span>{entry.content_excerpt}</span>}
                  </div>
                  <em className={entry.risk_level === "high" ? "is-failed" : "is-warning"}>
                    {businessLabel(entry.action)} / {businessLabel(entry.risk_level)}
                  </em>
                  <span>{formatDateTime(entry.created_at)}</span>
                </article>
              ))}
            </div>
          </section>

          <section className="admin-provider-table admin-audit-table admin-section-view admin-section-view--ai">
            <div className="admin-section-heading">
              <span className="eyebrow">模型调用</span>
              <h2>模型调用记录</h2>
            </div>
            <div className="admin-provider-list">
              {aiCallLogs.length === 0 && <p className="admin-empty-text">暂无模型调用记录。</p>}
              {aiCallLogs.slice(0, 12).map((entry) => (
                <article className="provider-row" key={entry.id}>
                  <div>
                    <strong>{entry.provider_name} / {entry.model_name}</strong>
                    <span>
                      {entry.session_id ?? "无会话"} · {businessLabel(entry.purpose)}
                      {entry.provider_request_id ? ` · ${entry.provider_request_id}` : ""}
                    </span>
                  </div>
                  <em className={entry.success ? "is-success" : "is-failed"}>
                    {entry.success ? "成功" : getApiErrorMessage({ detail: entry.error_message ?? undefined }, "调用失败")}
                  </em>
                  <span>
                    {entry.latency_ms != null ? `${entry.latency_ms}ms` : "未记录延迟"}
                    {entry.input_tokens != null || entry.output_tokens != null
                      ? ` · ${entry.input_tokens ?? 0}/${entry.output_tokens ?? 0} tokens`
                      : ""}
                    {entry.audio_duration_ms != null ? ` · 音频 ${Math.round(entry.audio_duration_ms / 1000)} 秒` : ""}
                    {entry.characters != null ? ` · ${entry.characters} 字` : ""}
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
