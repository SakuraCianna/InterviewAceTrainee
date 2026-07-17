import { FocusEvent, FormEvent, KeyboardEvent, ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { AppIcon } from "../../components/AppIcon";
import {
  CSRF_COOKIE_NAME,
  createIdempotencyKey,
  getApiErrorMessage,
  getCookie,
  listAdminTasks,
  retryTask,
  type AiTask,
} from "../../lib/api";
import { useEmailCodeCooldown } from "../../hooks/useEmailCodeCooldown";
import { normalizeEmail, retryAfterSeconds } from "../../lib/emailCooldown";
import { AdminLoginView } from "./AdminLoginView";
import { AdminSidebar } from "./AdminSidebar";
import { AdminTaskMonitor } from "./AdminTaskMonitor";
import { useAdminWriteLock } from "./hooks/useAdminWriteLock";
import { useVisiblePolling } from "./hooks/useVisiblePolling";
import {
  adjustUserCredits,
  createCustomerServiceNote,
  createRefundCase,
  getCurrentUser,
  getDashboardStats,
  getInterviewCoreHealth,
  getUserInterviewReport,
  issueVouchers,
  listAdminUsers,
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
  testProviderConfig,
  updateAdminUserRole,
  updateAdminUserStatus,
  updateProviderEnabled,
  updateRefundCase,
  updateSystemConfigValue,
} from "./adminApi";
import { AdminChart, barDashboardOption, donutDashboardOption, lineDashboardOption } from "./dashboardCharts";
import { configInputValue, parseConfigInput, parseOptionalAmountCents, parseOptionalInteger } from "./formUtils";
import { adminClasses } from "./adminStyles";
import {
  buildRefundCaseConfirmation,
  REFUND_ACCOUNTING_BOUNDARY_ITEMS,
  REFUND_CREATED_BOUNDARY,
  REFUND_CREDIT_FIELD_LABEL,
  REFUND_RESOLVED_NOTE,
  REFUND_STATUS_BOUNDARY,
} from "./refundAccounting";
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
const ADMIN_USER_PAGE_SIZE = 50;

const interviewTypeLabels: Record<string, string> = {
  job: "求职",
  postgraduate: "考研",
  civil_service: "公考",
  ielts: "雅思",
};

const adminSectionNavItems: { key: AdminSectionKey; label: string; icon: string }[] = [
  { key: "overview", label: "今日工作台", icon: "lucide:layout-dashboard" },
  { key: "users", label: "用户中心", icon: "lucide:users" },
  { key: "credits", label: "权益售后", icon: "lucide:headphones" },
  { key: "quality", label: "面试质量", icon: "lucide:bar-chart-2" },
  { key: "ai", label: "AI 健康", icon: "lucide:activity" },
  { key: "audit", label: "风控审计", icon: "lucide:shield-check" },
  { key: "system", label: "配置中心", icon: "lucide:settings-2" },
];

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

type StatusTone = "good" | "warning" | "danger" | "info" | "neutral";

type AdminTask = {
  id: string;
  title: string;
  detail: string;
  icon: string;
  tone: StatusTone;
  section: AdminSectionKey;
};

type AdminSelectOption = {
  value: string;
  label: string;
};

type AdminSelectProps = {
  value: string;
  options: AdminSelectOption[];
  ariaLabel: string;
  onChange: (value: string) => void;
};

function StatusChip({ tone = "neutral", icon, children }: { tone?: StatusTone; icon?: string; children: ReactNode }) {
  return (
    <span className={adminClasses("admin2-chip", `admin2-chip--${tone}`)}>
      {icon && <AppIcon icon={icon} size={15} />}
      {children}
    </span>
  );
}

function MetricCard({
  label,
  value,
  detail,
  icon,
  tone = "info",
}: {
  label: string;
  value: ReactNode;
  detail: ReactNode;
  icon: string;
  tone?: StatusTone;
}) {
  return (
    <article className={adminClasses("admin2-metric", `admin2-metric--${tone}`)}>
      <span className={adminClasses("admin2-metric-icon")}>
        <AppIcon icon={icon} size={20} />
      </span>
      <span>{label}</span>
      <strong>{value}</strong>
      <em>{detail}</em>
    </article>
  );
}

function AdminSelect({ value, options, ariaLabel, onChange }: AdminSelectProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const openFocusIndexRef = useRef<number | null>(null);
  const selectedOption = options.find((option) => option.value === value) ?? options[0];
  const selectedIndex = Math.max(0, options.findIndex((option) => option.value === selectedOption?.value));

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    const focusIndex = openFocusIndexRef.current ?? selectedIndex;
    openFocusIndexRef.current = null;
    setActiveIndex(focusIndex);
    window.requestAnimationFrame(() => {
      optionRefs.current[focusIndex]?.focus();
    });
  }, [isOpen, selectedIndex]);

  function closeWhenFocusLeaves(event: FocusEvent<HTMLDivElement>) {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
      setIsOpen(false);
    }
  }

  function openMenu(focusIndex = selectedIndex) {
    openFocusIndexRef.current = focusIndex;
    setActiveIndex(focusIndex);
    setIsOpen(true);
  }

  function closeMenu(restoreFocus = false) {
    setIsOpen(false);
    if (restoreFocus) {
      window.requestAnimationFrame(() => {
        triggerRef.current?.focus();
      });
    }
  }

  function handleTriggerKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      openMenu(selectedIndex);
      return;
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      openMenu(Math.max(0, options.length - 1));
      return;
    }
    if (event.key === "Escape") {
      closeMenu();
    }
  }

  function selectOption(option: AdminSelectOption, restoreFocus = true) {
    onChange(option.value);
    closeMenu(restoreFocus);
  }

  function focusOption(nextIndex: number) {
    const boundedIndex = Math.min(Math.max(nextIndex, 0), options.length - 1);
    setActiveIndex(boundedIndex);
    optionRefs.current[boundedIndex]?.focus();
  }

  function handleOptionKeyDown(event: KeyboardEvent<HTMLButtonElement>, option: AdminSelectOption, index: number) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      focusOption(index + 1);
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      focusOption(index - 1);
    }
    if (event.key === "Home") {
      event.preventDefault();
      focusOption(0);
    }
    if (event.key === "End") {
      event.preventDefault();
      focusOption(options.length - 1);
    }
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      selectOption(option);
    }
    if (event.key === "Escape") {
      event.preventDefault();
      closeMenu(true);
    }
  }

  return (
    <div className={adminClasses("admin-select", isOpen && "is-open")} onBlur={closeWhenFocusLeaves}>
      <button
        type="button"
        ref={triggerRef}
        className={adminClasses("admin-select-trigger")}
        aria-expanded={isOpen}
        aria-haspopup="listbox"
        aria-label={ariaLabel}
        onClick={() => {
          if (isOpen) {
            closeMenu();
            return;
          }
          openMenu(selectedIndex);
        }}
        onKeyDown={handleTriggerKeyDown}
      >
        <span>{selectedOption?.label ?? "请选择"}</span>
        <AppIcon icon="lucide:chevron-down" size={20} />
      </button>
      {isOpen && (
        <div className={adminClasses("admin-select-menu")} role="listbox" aria-label={ariaLabel}>
          {options.map((option, index) => (
            <button
              type="button"
              role="option"
              aria-selected={option.value === value}
              className={adminClasses(option.value === value && "is-selected")}
              key={option.value}
              ref={(element) => {
                optionRefs.current[index] = element;
              }}
              tabIndex={index === activeIndex ? 0 : -1}
              onMouseDown={(event) => event.preventDefault()}
              onFocus={() => setActiveIndex(index)}
              onKeyDown={(event) => handleOptionKeyDown(event, option, index)}
              onClick={() => selectOption(option)}
            >
              {option.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function BooleanConfigSelect({ config }: { config: SystemConfig }) {
  const [booleanValue, setBooleanValue] = useState(String(config.value));

  useEffect(() => {
    setBooleanValue(String(config.value));
  }, [config.value]);

  return (
    <>
      <input type="hidden" name="value" value={booleanValue} />
      <AdminSelect
        ariaLabel={`选择${config.key}状态`}
        value={booleanValue}
        options={[
          { value: "true", label: "开启" },
          { value: "false", label: "关闭" },
        ]}
        onChange={setBooleanValue}
      />
    </>
  );
}

function adminSectionFromHash(hash: string): AdminSectionKey | null {
  const key = hash.replace(/^#admin-/, "") as AdminSectionKey;
  return adminSectionNavItems.some((item) => item.key === key) ? key : null;
}

function configGroupLabel(config: SystemConfig) {
  const key = config.key.toLowerCase();
  if (key.includes("email") || key.includes("mail") || key.includes("resend")) {
    return "邮件与验证码";
  }
  if (key.includes("auth") || key.includes("login") || key.includes("password") || key.includes("csrf")) {
    return "登录与安全";
  }
  if (key.includes("interview") || key.includes("capacity") || key.includes("credit") || key.includes("voucher")) {
    return "面试与权益";
  }
  if (key.includes("llm") || key.includes("asr") || key.includes("tts") || key.includes("model") || key.includes("provider")) {
    return "AI 服务";
  }
  return "基础配置";
}

type SelectedUserSnapshot = Omit<AdminUserSearchItem, "role" | "is_active"> & Partial<Pick<AdminUserSearchItem, "role" | "is_active">>;

function dashboardUserToSnapshot(user: AdminDashboardStats["top_users"][number]): SelectedUserSnapshot {
  return {
    email: user.email,
    credit_balance: user.credit_balance,
    total_interviews: user.total_interviews,
    completed_interviews: user.completed_interviews,
    last_interview_at: user.last_interview_at,
  };
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
  const [userListTotal, setUserListTotal] = useState(0);
  const [userListOffset, setUserListOffset] = useState(0);
  const [userListHasMore, setUserListHasMore] = useState(false);
  const [userListTotalIsEstimated, setUserListTotalIsEstimated] = useState(false);
  const [isUserListLoading, setIsUserListLoading] = useState(false);
  const [selectedUserHistory, setSelectedUserHistory] = useState<AdminInterviewHistoryItem[]>([]);
  const [selectedUserEmail, setSelectedUserEmail] = useState("");
  const [selectedUserSnapshot, setSelectedUserSnapshot] = useState<SelectedUserSnapshot | null>(null);
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
  const [creditsSubTab, setCreditsSubTab] = useState<"adjust" | "refunds">("adjust");
  const [auditSubTab, setAuditSubTab] = useState<"login" | "safety" | "operations">("login");
  const [auditLoginFilter, setAuditLoginFilter] = useState("");
  const [loginLogFilter, setLoginLogFilter] = useState<"all" | "success" | "failed">("all");
  const [safetyLogFilter, setSafetyLogFilter] = useState<"all" | "high" | "medium" | "low">("all");
  const [aiLogFilter, setAiLogFilter] = useState<"all" | "success" | "failed">("all");
  const [aiTasks, setAiTasks] = useState<AiTask[]>([]);
  const [areAiTasksLoading, setAreAiTasksLoading] = useState(false);
  const loadedSectionsRef = useRef(new Set<AdminSectionKey>());
  const { isLocked, runLocked } = useAdminWriteLock();

  const dashboardChartOptions = useMemo(() => {
    if (!dashboardStats) {
      return null;
    }
    return {
      trend: lineDashboardOption(dashboardStats),
      moduleMix: donutDashboardOption(dashboardStats.interview_type_distribution, ["#1a73e8", "#34a853", "#fbbc04", "#ea4335"]),
      sessionStatus: donutDashboardOption(dashboardStats.session_status_distribution, ["#1a73e8", "#34a853", "#fbbc04", "#ea4335"]),
      aiQuality: barDashboardOption(dashboardStats.ai_call_success_distribution, "AI 调用", "#1a73e8"),
      loginOutcome: barDashboardOption(dashboardStats.login_outcome_distribution, "登录", "#ea4335"),
      refunds: donutDashboardOption(dashboardStats.refund_status_distribution, ["#fbbc04", "#1a73e8", "#34a853", "#ea4335"]),
    };
  }, [dashboardStats]);

  const userListSummary = useMemo(() => {
    return {
      total: userListTotal,
      active: userSearchResults.filter((user) => user.is_active).length,
      disabled: userSearchResults.filter((user) => !user.is_active).length,
      admins: userSearchResults.filter((user) => user.role === "admin").length,
      creditBalance: userSearchResults.reduce((sum, user) => sum + user.credit_balance, 0),
      interviews: userSearchResults.reduce((sum, user) => sum + user.total_interviews, 0),
    };
  }, [userListTotal, userSearchResults]);

  const selectedUser = useMemo(() => {
    const listedUser = userSearchResults.find((user) => user.email === selectedUserEmail);
    if (listedUser) {
      return listedUser;
    }
    if (selectedUserSnapshot?.email === selectedUserEmail) {
      return selectedUserSnapshot;
    }
    return null;
  }, [selectedUserEmail, selectedUserSnapshot, userSearchResults]);

  const userListRange = useMemo(() => {
    if (userListTotal === 0) {
      return "0 / 0";
    }
    const rangeStart = userListOffset + 1;
    const rangeEnd = Math.min(userListOffset + userSearchResults.length, userListTotal);
    const totalLabel = userListTotalIsEstimated ? `至少 ${userListTotal}` : `${userListTotal}`;
    return `${rangeStart}-${rangeEnd} / ${totalLabel}`;
  }, [userListOffset, userListTotal, userListTotalIsEstimated, userSearchResults.length]);

  const taskQueue = useMemo<AdminTask[]>(() => {
    const tasks: AdminTask[] = [];
    const openRefundCount = refundCases.filter((entry) => entry.status === "open" || entry.status === "processing").length;
    const failedAiCount = aiCallLogs.filter((entry) => !entry.success).length;
    const failedTaskCount = aiTasks.filter((task) => task.status === "FAILED").length;
    const failedLoginCount = authLoginLogs.filter((entry) => !entry.success).length;
    const blockedSafetyCount = contentSafetyLogs.filter((entry) => entry.action !== "allowed").length;

    if (!interviewCoreHealth) {
      tasks.push({
        id: "core-pending",
        title: "面试核心尚未读取",
        detail: interviewCoreHealthMessage,
        icon: "lucide:shield-question",
        tone: "warning",
        section: "quality",
      });
    } else if (!interviewCoreHealth.ready) {
      tasks.push({
        id: "core-degraded",
        title: "面试核心退化",
        detail: interviewCoreHealth.failure_summary || "请检查能力卡片、向量覆盖和召回探针。",
        icon: "lucide:shield-alert",
        tone: "danger",
        section: "quality",
      });
    }
    if (openRefundCount > 0) {
      tasks.push({
        id: "refunds",
        title: `${openRefundCount} 个售后纠纷待处理`,
        detail: "优先核对训练报告、次数流水、客服备注和退款状态。",
        icon: "lucide:receipt-text",
        tone: "warning",
        section: "credits",
      });
    }
    if (failedAiCount > 0) {
      tasks.push({
        id: "ai-failures",
        title: `${failedAiCount} 条 AI 调用失败`,
        detail: "查看供应商、模型、用途、错误和延迟成本，判断是否需要切换服务。",
        icon: "lucide:bot-off",
        tone: "danger",
        section: "ai",
      });
    }
    if (failedTaskCount > 0) {
      tasks.push({
        id: "worker-failures",
        title: `${failedTaskCount} 个 Worker 任务需处理`,
        detail: "查看错误码与自动重试次数，必要时人工重新入队。",
        icon: "lucide:server-cog",
        tone: "danger",
        section: "ai",
      });
    }
    if (blockedSafetyCount > 0) {
      tasks.push({
        id: "content-safety",
        title: `${blockedSafetyCount} 条内容安全记录`,
        detail: "复核高风险输入来源、命中类别和用户会话。",
        icon: "lucide:shield-ban",
        tone: "danger",
        section: "audit",
      });
    }
    if (failedLoginCount > 0) {
      tasks.push({
        id: "failed-logins",
        title: `${failedLoginCount} 次登录失败`,
        detail: "关注异常 IP、验证码失败和管理员账号尝试。",
        icon: "lucide:key-round",
        tone: "warning",
        section: "audit",
      });
    }
    if (tasks.length === 0) {
      tasks.push({
        id: "all-clear",
        title: "当前没有高优先级待办",
        detail: "可以继续查看用户活跃、报告质量和服务趋势。",
        icon: "lucide:circle-check-big",
        tone: "good",
        section: "overview",
      });
    }
    return tasks;
  }, [aiCallLogs, aiTasks, authLoginLogs, contentSafetyLogs, interviewCoreHealth, interviewCoreHealthMessage, refundCases]);

  const groupedSystemConfigs = useMemo(() => {
    const groups = new Map<string, SystemConfig[]>();
    systemConfigs.forEach((config) => {
      const group = configGroupLabel(config);
      groups.set(group, [...(groups.get(group) ?? []), config]);
    });
    return Array.from(groups.entries());
  }, [systemConfigs]);

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
    if (!currentUser) {
      return;
    }
    void loadSectionData(activeAdminSection);
  }, [activeAdminSection, currentUser]);

  useVisiblePolling({
    enabled: Boolean(currentUser && (activeAdminSection === "overview" || activeAdminSection === "ai")),
    poll: (signal) => loadAiTasks(signal, true),
  });

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
      parts.push(entry.note);
    }
    return parts.join(" · ");
  }

  const selectedCreditReason = creditReasonOptions.find((option) => option.value === creditReason) ?? creditReasonOptions[0];
  const selectedVoucherReason = voucherReasonOptions.find((option) => option.value === voucherReason) ?? voucherReasonOptions[0];
  const consoleLayoutRef = useRef<HTMLDivElement | null>(null);

  function selectAdminSection(section: AdminSectionKey) {
    setActiveAdminSection(section);
    window.history.replaceState(null, "", `#admin-${section}`);
    window.requestAnimationFrame(() => {
      consoleLayoutRef.current?.scrollTo({ top: 0 });
    });
  }

  function clearSelectedUserContext() {
    setSelectedUserEmail("");
    setSelectedUserSnapshot(null);
    setSelectedUserHistory([]);
    setSelectedReport(null);
    setReportMessage("");
    setCustomerServiceNotes([]);
    setCreditLedger([]);
    setAuthLoginLogs([]);
    setRefundCases([]);
    void Promise.all([loadAuthLoginLogs(), loadRefundCases()]);
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
    loadedSectionsRef.current.clear();
  }

  async function loadProviders() {
    const { response, data } = await listProviders();
    if (!response.ok) {
      setMessage("AI 服务状态读取失败。");
      return;
    }
    setProviders(data);
  }

  async function loadAiTasks(signal?: AbortSignal, silent = false) {
    if (!silent) {
      setAreAiTasksLoading(true);
    }
    try {
      const page = await listAdminTasks({ limit: 25, signal });
      setAiTasks(page.items);
    } catch {
      if (signal?.aborted) {
        return;
      }
      setMessage("AI 任务队列读取失败，请检查 API 与 Worker 状态。");
    } finally {
      if (!silent && !signal?.aborted) {
        setAreAiTasksLoading(false);
      }
    }
  }

  /** 每个分区首次进入时才请求其数据；force 仅供用户主动刷新使用。 */
  async function loadSectionData(section: AdminSectionKey, force = false) {
    if (!force && loadedSectionsRef.current.has(section)) {
      return;
    }
    const loaders: Record<AdminSectionKey, Array<() => Promise<unknown>>> = {
      overview: [loadDashboardStats, loadInterviewCoreHealth, () => loadRefundCases(), loadAiTasks],
      users: [() => loadUsers()],
      credits: [() => loadRefundCases(), () => loadUsers()],
      quality: [loadInterviewCoreHealth],
      ai: [loadProviders, loadAiCallLogs, loadAiTasks, loadDashboardStats],
      audit: [loadAuditLogs, loadContentSafetyLogs, () => loadAuthLoginLogs(), () => loadRefundCases()],
      system: [loadSystemConfigs],
    };
    await Promise.allSettled(loaders[section].map((load) => load()));
    loadedSectionsRef.current.add(section);
  }

  async function runAdminWrite<T>(
    lockKey: string,
    networkErrorMessage: string,
    operation: (idempotencyKey: string) => Promise<T>,
  ) {
    return runLocked(lockKey, async () => {
      try {
        return await operation(createIdempotencyKey());
      } catch {
        setMessage(networkErrorMessage);
        return undefined;
      }
    });
  }

  async function retryAiTask(task: AiTask) {
    await runAdminWrite(`task-retry:${task.id}`, "任务重新入队失败，请刷新后确认任务状态。", async (operationKey) => {
      await retryTask(task, undefined, operationKey);
      setMessage(`任务 ${task.id} 已重新入队。`);
      await loadAiTasks();
    });
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

  async function loadUsers(query = userSearchQuery.trim(), showMessage = false, offset = userListOffset) {
    setIsUserListLoading(true);
    let result: Awaited<ReturnType<typeof listAdminUsers>>;
    try {
      result = await listAdminUsers(query, ADMIN_USER_PAGE_SIZE, offset);
    } catch {
      setIsUserListLoading(false);
      setMessage("用户列表读取失败，请确认本地后端服务可访问。");
      return;
    }
    setIsUserListLoading(false);
    const { response, data } = result;
    if (!response.ok) {
      setMessage("用户列表读取失败，请确认管理员会话仍然有效。");
      return;
    }

    const users = data.items;
    setUserSearchResults(users);
    setUserListTotal(data.total);
    setUserListOffset(data.offset);
    setUserListHasMore(data.has_more);
    setUserListTotalIsEstimated(data.total_is_estimated);
    const listedSelectedUser = users.find((user) => user.email === selectedUserEmail);
    if (listedSelectedUser) {
      setSelectedUserSnapshot(listedSelectedUser);
    } else if (selectedUserEmail && selectedUserSnapshot?.email !== selectedUserEmail) {
      clearSelectedUserContext();
    }
    if (showMessage) {
      const rangeStart = data.total === 0 ? 0 : data.offset + 1;
      const rangeEnd = Math.min(data.offset + users.length, data.total);
      const totalLabel = data.total_is_estimated ? `至少 ${data.total}` : `${data.total}`;
      setMessage(query ? `找到 ${totalLabel} 个用户记录，当前显示 ${rangeStart}-${rangeEnd}。` : `已加载用户列表 ${rangeStart}-${rangeEnd} / ${totalLabel}。`);
    }
  }

  async function searchUsers(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    await loadUsers(userSearchQuery.trim(), true, 0);
  }

  async function loadUserHistory(userEmail: string, userSnapshot?: SelectedUserSnapshot) {
    setSelectedUserEmail(userEmail);
    setSelectedUserSnapshot(userSnapshot ?? userSearchResults.find((user) => user.email === userEmail) ?? null);
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

  async function requestAdminCode(captchaToken: string) {
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
      result = await requestAdminEmailCode(normalizeEmail(loginEmail), captchaToken);
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

  async function submitAdminLogin(event: FormEvent<HTMLFormElement>, captchaToken: string) {
    event.preventDefault();
    if (isSubmittingAdminLogin) {
      return;
    }
    setIsSubmittingAdminLogin(true);
    let result: Awaited<ReturnType<typeof loginAdmin>>;
    try {
      result = await loginAdmin(loginEmail, loginPassword, loginCode, captchaToken);
    } catch {
      setIsSubmittingAdminLogin(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const { response, data } = result;
    if (!response.ok || !data.email || data.role !== "admin") {
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
    const normalizedCreditUser = normalizeEmail(creditUser);
    const reason = creditReason.trim() || (amount > 0 ? "manual_grant" : "manual_adjustment");
    const note = creditNote.trim();
    if (!normalizedCreditUser || Number.isNaN(amount) || amount === 0 || reason.length < 2) {
      setMessage("请填写用户邮箱、非 0 次数和调整原因。");
      return;
    }
    if (
      !window.confirm(
        `确认为 ${normalizedCreditUser} ${amount > 0 ? "增加" : "扣减"} ${Math.abs(amount)} 次？\n原因：${businessLabel(reason)}${
          note ? `\n备注：${note}` : ""
        }`,
      )
    ) {
      return;
    }

    await runAdminWrite("credit-adjust", "次数调整请求失败，请检查网络后确认用户流水。", async (operationKey) => {
      const { response, data } = await adjustUserCredits(normalizedCreditUser, {
        change_amount: amount,
        reason,
        note: note || undefined,
      }, operationKey);
      if (!response.ok) {
        setMessage(`次数调整失败：${getApiErrorMessage(data, "请检查用户邮箱和次数。")}`);
        return;
      }

      setMessage(`${normalizedCreditUser} 已调整 ${amount} 次，当前余额 ${data.balance_after}。`);
      setCreditAmount("1");
      setCreditReason("manual_grant");
      setCreditNote("");
      await Promise.allSettled([loadDashboardStats(), loadAuditLogs(), loadCreditLedger(normalizedCreditUser), loadUsers(userSearchQuery.trim())]);
    });
  }

  async function submitVoucherIssue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const quantity = Number.parseInt(voucherQuantity, 10);
    const userEmails = Array.from(new Set(voucherEmails
      .split(/[\s,;]+/)
      .map((email) => normalizeEmail(email))
      .filter(Boolean)));
    const reason = voucherReason.trim() || "manual_voucher_grant";
    const note = voucherNote.trim();
    if (!voucherAllUsers && userEmails.length === 0) {
      setMessage("请填写至少一个用户邮箱，或选择发放给全部普通用户。");
      return;
    }
    if (!Number.isFinite(quantity) || quantity <= 0) {
      setMessage("体验券数量需要填写为正整数。");
      return;
    }
    const voucherTargetLabel = voucherAllUsers ? "全部启用中的普通用户" : `${userEmails.length} 个指定用户`;
    if (
      !window.confirm(
        `确认向 ${voucherTargetLabel} 发放体验券？\n每人 ${quantity} 张，原因：${businessLabel(reason)}${
          voucherAllUsers ? "\n这是批量发放操作，请确认范围无误。" : ""
        }${note ? `\n备注：${note}` : ""}`,
      )
    ) {
      return;
    }

    await runAdminWrite("voucher-issue", "体验券发放请求失败，请检查网络后确认审计日志。", async (operationKey) => {
      const { response, data } = await issueVouchers({
        user_emails: voucherAllUsers ? [] : userEmails,
        issue_all_active_users: voucherAllUsers,
        quantity,
        voucher_type: "admin_grant",
        reason,
        note: note || undefined,
      }, operationKey);
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
      await Promise.allSettled([loadDashboardStats(), loadAuditLogs()]);
    });
  }

  async function submitCustomerServiceNote(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedUserEmail) {
      setMessage("请先在用户中心选择一个用户。");
      return;
    }
    const content = noteContent.trim();
    const category = noteCategory.trim() || "general";
    if (content.length < 2) {
      setMessage("客服备注至少需要 2 个字符。");
      return;
    }

    const targetUserEmail = selectedUserEmail;
    await runAdminWrite(`note-create:${targetUserEmail}`, "客服备注保存请求失败，请检查网络后确认备注列表。", async (operationKey) => {
      const { response, data } = await createCustomerServiceNote(targetUserEmail, {
        category,
        content,
        related_session_id: noteSessionId.trim() || undefined,
      }, operationKey);
      if (!response.ok) {
        setMessage(`客服备注保存失败：${getApiErrorMessage(data, "请检查备注内容。")}`);
        return;
      }

      setNoteCategory("general");
      setNoteContent("");
      setNoteSessionId("");
      setMessage(`已为 ${targetUserEmail} 添加客服备注。`);
      await Promise.allSettled([loadCustomerServiceNotes(targetUserEmail), loadAuditLogs()]);
    });
  }

  async function submitRefundCase(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedUserEmail) {
      setMessage("请先在用户中心选择一个用户。");
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
      setMessage(`${REFUND_CREDIT_FIELD_LABEL}需要填写整数，例如 1、0 或 -1。`);
      return;
    }
    if (
      !window.confirm(
        buildRefundCaseConfirmation({
          email: selectedUserEmail,
          reasonLabel: businessLabel(reason),
          amountLabel: formatCents(amountCents),
          proposedCreditAdjustment: creditAdjustment ?? 0,
        }),
      )
    ) {
      return;
    }

    const targetUserEmail = selectedUserEmail;
    await runAdminWrite(`refund-create:${targetUserEmail}`, "退款纠纷创建请求失败，请检查网络后确认售后记录。", async (operationKey) => {
      const { response, data } = await createRefundCase(targetUserEmail, {
        reason,
        description,
        amount_cents: amountCents,
        currency: "CNY",
        credit_adjustment: creditAdjustment,
        related_session_id: refundSessionId.trim() || undefined,
      }, operationKey);
      if (!response.ok) {
        setMessage(`退款纠纷记录创建失败：${getApiErrorMessage(data, "请检查纠纷记录内容。")}`);
        return;
      }

      setRefundReason("refund_request");
      setRefundDescription("");
      setRefundAmountYuan("");
      setRefundCreditAdjustment("");
      setRefundSessionId("");
      setMessage(`已创建 ${targetUserEmail} 的退款纠纷记录；${REFUND_CREATED_BOUNDARY}`);
      await Promise.allSettled([loadDashboardStats(), loadRefundCases(targetUserEmail), loadAuditLogs()]);
    });
  }

  async function updateRefundCaseStatus(refundCase: RefundCaseEntry, statusValue: string) {
    if (refundCase.status === statusValue) {
      setMessage(`退款纠纷 ${refundCase.id} 已经是 ${businessLabel(statusValue)} 状态。`);
      return;
    }
    if (
      !window.confirm(
        `确认将 ${refundCase.user_email} 的退款纠纷 ${refundCase.id} 从 ${businessLabel(refundCase.status)} 更新为 ${businessLabel(statusValue)}？`,
      )
    ) {
      return;
    }
    await runAdminWrite(`refund-status:${refundCase.id}`, "退款纠纷状态更新请求失败，请检查网络后确认记录。", async (operationKey) => {
      const { response, data } = await updateRefundCase(refundCase.id, {
        status: statusValue,
        resolution:
          statusValue === "resolved"
            ? refundCase.resolution || REFUND_RESOLVED_NOTE
            : refundCase.resolution,
      }, operationKey);
      if (!response.ok) {
        setMessage(`退款纠纷状态更新失败：${getApiErrorMessage(data, "请稍后重试。")}`);
        return;
      }

      setMessage(`退款纠纷 ${refundCase.id} 已更新为 ${businessLabel(statusValue)}；${REFUND_STATUS_BOUNDARY}`);
      await Promise.allSettled([loadDashboardStats(), loadRefundCases(selectedUserEmail || undefined), loadAuditLogs()]);
    });
  }

  async function toggleProvider(provider: ProviderConfig) {
    if (!window.confirm(`确认${provider.enabled ? "停用" : "启用"} ${provider.id}？这会影响对应 AI 服务路由。`)) {
      return;
    }
    await runAdminWrite(`provider-toggle:${provider.id}`, "模型启停请求失败，请检查网络后确认供应商状态。", async (operationKey) => {
      const { response } = await updateProviderEnabled(provider.id, !provider.enabled, operationKey);
      if (!response.ok) {
        setMessage("模型启停更新失败。");
        return;
      }
      await Promise.allSettled([loadProviders(), loadAuditLogs()]);
      setMessage(`${provider.id} 已${provider.enabled ? "停用" : "启用"}。`);
    });
  }

  async function testProvider(provider: ProviderConfig) {
    await runAdminWrite(`provider-test:${provider.id}`, "供应商校验请求失败，请检查网络和服务状态。", async (operationKey) => {
      setProviderTestResults((previous) => ({ ...previous, [provider.id]: "校验中" }));
      try {
        const { response, data } = await testProviderConfig(provider.id, operationKey);
        const resultDetail = getApiErrorMessage(data, "服务测试未返回明确结果。");
        const resultText = response.ok
          ? `${data.success ? "通过" : "未通过"}：${resultDetail}`
          : `测试失败：${resultDetail}`;
        setProviderTestResults((previous) => ({ ...previous, [provider.id]: resultText }));
        await Promise.allSettled([loadAuditLogs()]);
      } catch (error) {
        setProviderTestResults((previous) => ({ ...previous, [provider.id]: "测试失败：网络或供应商服务异常，请稍后重试。" }));
        throw error;
      }
    });
  }

  async function updateUserStatus(user: AdminUserSearchItem, isActive: boolean) {
    if (!window.confirm(`确认${isActive ? "启用" : "禁用"}用户 ${user.email}？`)) {
      return;
    }
    await runAdminWrite(`user-status:${user.email}`, "用户状态更新请求失败，请检查网络后确认用户状态。", async (operationKey) => {
      const { response, data } = await updateAdminUserStatus(user.email, isActive, operationKey);
      if (!response.ok) {
        setMessage(`用户状态更新失败：${getApiErrorMessage(data, "请稍后重试。")}`);
        return;
      }
      setMessage(`${user.email} 已${isActive ? "启用" : "禁用"}。`);
      await Promise.allSettled([loadDashboardStats(), loadUsers(userSearchQuery.trim()), loadAuditLogs()]);
    });
  }

  async function updateUserRole(user: AdminUserSearchItem, role: "user" | "admin") {
    if (!window.confirm(`确认将 ${user.email} ${role === "admin" ? "设为管理员" : "撤销管理员权限"}？`)) {
      return;
    }
    await runAdminWrite(`user-role:${user.email}`, "用户角色更新请求失败，请检查网络后确认用户角色。", async (operationKey) => {
      const { response, data } = await updateAdminUserRole(user.email, role, operationKey);
      if (!response.ok) {
        setMessage(`用户角色更新失败：${getApiErrorMessage(data, "请稍后重试。")}`);
        return;
      }
      setUserSearchResults((previous) =>
        previous.map((item) => (item.email === user.email ? { ...item, role: data.role ?? role } : item)),
      );
      setMessage(`${user.email} 已${role === "admin" ? "设为管理员" : "撤销管理员"}，该账号需要重新登录后生效。`);
      await Promise.allSettled([loadDashboardStats(), loadAuditLogs()]);
    });
  }

  async function updateSystemConfig(config: SystemConfig, rawValue: string) {
    let value: SystemConfig["value"];
    try {
      value = parseConfigInput(config.value, rawValue);
    } catch {
      setMessage(`${config.key} 需要合法 JSON。`);
      return;
    }
    await runAdminWrite(`system-config:${config.key}`, "系统配置保存请求失败，请检查网络后确认配置值。", async (operationKey) => {
      const { response, data } = await updateSystemConfigValue(config.key, value, operationKey);
      if (!response.ok) {
        setMessage(`系统配置保存失败：${getApiErrorMessage(data, "请检查配置值。")}`);
        return;
      }
      setMessage(`已更新系统配置：${data.key}`);
      await Promise.allSettled([loadSystemConfigs(), loadAuditLogs()]);
    });
  }

  async function refreshAdminData() {
    await loadSectionData(activeAdminSection, true);
    setMessage("后台数据已刷新。");
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
    setAiTasks([]);
    loadedSectionsRef.current.clear();
    setUserSearchResults([]);
    setSelectedUserHistory([]);
    setSelectedUserEmail("");
    setSelectedUserSnapshot(null);
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
    setCreditsSubTab("adjust");
    setAuditSubTab("login");
    setAuditLoginFilter("");
    setLoginLogFilter("all");
    setSafetyLogFilter("all");
    setAiLogFilter("all");
    setMessage("已退出后台，请重新完成管理员后台登录。");
    window.location.assign("/");
  }

  function renderUserDrawer() {
    if (!selectedUserEmail) {
      return (
        <aside className={adminClasses("admin2-drawer admin2-drawer--empty")}>
          <AppIcon icon="lucide:panel-right-open" size={28} />
          <h3>选择一个用户</h3>
          <p>右侧会汇总训练、报告、权益流水、客服备注、退款纠纷和登录记录。</p>
        </aside>
      );
    }

    return (
      <aside className={adminClasses("admin2-drawer")}>
        <div className={adminClasses("admin2-drawer-head")}>
          <div className={adminClasses("admin2-user-identity")}>
            <span className={adminClasses("admin2-user-eyebrow")}>用户详情</span>
            <h3>{selectedUserEmail}</h3>
            <p>
              {selectedUser
                ? `${selectedUser.role ? businessLabel(selectedUser.role) : "角色以用户中心为准"} · ${
                    selectedUser.is_active == null ? "状态以用户中心为准" : selectedUser.is_active ? "启用中" : "已停用"
                  } · 余额 ${selectedUser.credit_balance} 次`
                : "正在读取用户上下文"}
            </p>
          </div>
          <button type="button" className={adminClasses("admin2-icon-button")} onClick={clearSelectedUserContext} aria-label="关闭用户详情">
            <AppIcon icon="lucide:x" size={18} />
          </button>
        </div>

        <dl className={adminClasses("admin2-user-kpis")} aria-label="用户概览指标">
          <div className={adminClasses("admin2-user-kpi")}>
            <dt>剩余次数</dt>
            <dd>{selectedUser?.credit_balance ?? 0}</dd>
          </div>
          <div className={adminClasses("admin2-user-kpi")}>
            <dt>训练记录</dt>
            <dd>{selectedUserHistory.length}</dd>
          </div>
          <div className={adminClasses("admin2-user-kpi")}>
            <dt>客服备注</dt>
            <dd>{customerServiceNotes.length}</dd>
          </div>
          <div className={adminClasses("admin2-user-kpi")}>
            <dt>售后记录</dt>
            <dd>{refundCases.length}</dd>
          </div>
        </dl>

        <section className={adminClasses("admin2-drawer-section")}>
          <h4>最近训练</h4>
          {selectedUserHistory.length === 0 && <p className={adminClasses("admin2-empty")}>暂无训练记录。</p>}
          {selectedUserHistory.slice(0, 6).map((item) => (
            <article className={adminClasses("admin2-feed-item")} key={item.session_id}>
              <div>
                <strong>{interviewTypeLabel(item.interview_type)}</strong>
                <span>{businessLabel(item.status)} · 第 {item.current_step_index + 1}/{item.total_steps} 步 · {formatDateTime(item.created_at)}</span>
              </div>
              <em>{item.report_total_score ?? "未出分"}</em>
              <button
                type="button"
                disabled={item.report_total_score == null}
                onClick={() => void loadInterviewReport(selectedUserEmail, item.session_id)}
              >
                报告
              </button>
            </article>
          ))}
        </section>

        {(reportMessage || selectedReport) && (
          <section className={adminClasses("admin2-drawer-section admin2-report-panel")}>
            <div className={adminClasses("admin2-report-title")}>
              <h4>报告复核</h4>
              {selectedReport && <strong>{selectedReport.total_score}</strong>}
            </div>
            {reportMessage && <p className={adminClasses("admin2-note")}>{reportMessage}</p>}
            {selectedReport && (
              <>
                <p>{selectedReport.summary}</p>
                <div className={adminClasses("admin2-mini-grid")}>
                  <span><b>{selectedReport.readiness_level || "待复盘"}</b>准备度</span>
                  <span><b>{selectedReport.dimensions.length}</b>评分维度</span>
                  <span><b>{selectedReport.turns.length}</b>问答轮次</span>
                </div>
                <div className={adminClasses("admin2-report-columns")}>
                  <section>
                    <h5>风险提醒</h5>
                    {(selectedReport.risk_flags.length ? selectedReport.risk_flags : ["暂无风险提醒"]).slice(0, 4).map((item) => <p key={item}>{item}</p>)}
                  </section>
                  <section>
                    <h5>优先动作</h5>
                    {(selectedReport.priority_actions.length ? selectedReport.priority_actions : selectedReport.next_plan).slice(0, 4).map((item) => <p key={item}>{item}</p>)}
                  </section>
                </div>
              </>
            )}
          </section>
        )}

        <section className={adminClasses("admin2-drawer-section")}>
          <h4>权益流水</h4>
          {creditLedger.length === 0 && <p className={adminClasses("admin2-empty")}>暂无次数流水。</p>}
          {creditLedger.slice(0, 5).map((entry) => (
            <article className={adminClasses("admin2-feed-item")} key={entry.id}>
              <div>
                <strong>{entry.change_amount > 0 ? `+${entry.change_amount}` : entry.change_amount}</strong>
                <span>{formatLedgerReason(entry)}</span>
              </div>
              <small>{formatDateTime(entry.created_at)}</small>
            </article>
          ))}
        </section>

        <section className={adminClasses("admin2-drawer-section")}>
          <h4>客服与售后</h4>
          <div className={adminClasses("admin2-report-columns")}>
            <section>
              <h5>客服备注</h5>
              {customerServiceNotes.length === 0 && <p className={adminClasses("admin2-empty")}>暂无备注。</p>}
              {customerServiceNotes.slice(0, 3).map((note) => (
                <p key={note.id}>{businessLabel(note.category)} · {note.content}</p>
              ))}
            </section>
            <section>
              <h5>退款纠纷</h5>
              {refundCases.length === 0 && <p className={adminClasses("admin2-empty")}>暂无纠纷。</p>}
              {refundCases.slice(0, 3).map((entry) => (
                <p key={entry.id}>{businessLabel(entry.status)} · {businessLabel(entry.reason)} · {formatCents(entry.amount_cents, entry.currency)}</p>
              ))}
            </section>
          </div>
        </section>
      </aside>
    );
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
    <main className={adminClasses("workspace-page admin-page admin-page--authed admin2-page")} data-admin-section={activeAdminSection}>
      <AdminSidebar
        activeSection={activeAdminSection}
        currentUser={currentUser}
        navItems={adminSectionNavItems}
        statusMessage={adminStatusMessage}
        onSelectSection={selectAdminSection}
        onRefresh={() => void refreshAdminData()}
        onLogout={() => void logout()}
      />
      <div className={adminClasses("admin-console-layout admin2-layout")} ref={consoleLayoutRef}>
        <section className={adminClasses("admin2-content")}>
          {activeAdminSection === "overview" && (
            <section className={adminClasses("admin2-section")}>
              <div className={adminClasses("admin2-metrics-grid")}>
                <MetricCard label="总用户" value={dashboardStats?.overview.total_users.toLocaleString("zh-CN") ?? "暂无"} detail={`启用 ${dashboardStats?.overview.active_users ?? 0} / 停用 ${dashboardStats?.overview.disabled_users ?? 0}`} icon="lucide:users" />
                <MetricCard label="今日训练" value={dashboardStats?.overview.today_sessions ?? "暂无"} detail={`总场次 ${dashboardStats?.overview.total_sessions ?? 0} / 进行中 ${dashboardStats?.overview.active_sessions ?? 0}`} icon="lucide:mic-2" />
                <MetricCard label="报告产出" value={dashboardStats?.overview.total_reports.toLocaleString("zh-CN") ?? "暂无"} detail={`均分 ${dashboardStats?.overview.average_report_score ?? "暂无"}`} icon="lucide:file-check-2" tone="good" />
                <MetricCard label="AI 成功率" value={formatDashboardRate(dashboardStats?.overview.ai_success_rate)} detail={`失败调用 ${aiCallLogs.filter((entry) => !entry.success).length}`} icon="lucide:bot" tone={aiCallLogs.some((entry) => !entry.success) ? "danger" : "info"} />
                <MetricCard label="售后待办" value={dashboardStats?.overview.open_refund_cases ?? refundCases.filter((entry) => entry.status !== "resolved").length} detail="退款 / 纠纷 / 服务补偿" icon="lucide:receipt-text" tone="warning" />
                <MetricCard label="剩余次数" value={dashboardStats?.overview.total_credit_balance.toLocaleString("zh-CN") ?? "暂无"} detail={`累计发放 ${dashboardStats?.overview.total_credit_granted ?? 0}`} icon="lucide:coins" />
              </div>

              <div className={adminClasses("admin2-grid admin2-grid--dashboard")}>
                <section className={adminClasses("admin2-panel")}>
                  <div className={adminClasses("admin2-panel-head")}>
                    <div>
                      <h3>待处理队列</h3>
                      <p>按风险优先级聚合后台现在需要看的事。</p>
                    </div>
                  </div>
                  <div className={adminClasses("admin2-task-list")}>
                    {taskQueue.map((task) => (
                      <button type="button" className={adminClasses("admin2-task", `admin2-task--${task.tone}`)} key={task.id} onClick={() => selectAdminSection(task.section)}>
                        <AppIcon icon={task.icon} size={20} />
                        <span>
                          <strong>{task.title}</strong>
                          <em>{task.detail}</em>
                        </span>
                        <AppIcon icon="lucide:chevron-right" size={18} />
                      </button>
                    ))}
                  </div>
                </section>

                <section className={adminClasses("admin2-panel admin2-panel--wide")}>
                  <div className={adminClasses("admin2-panel-head")}>
                    <div>
                      <h3>近 14 天业务趋势</h3>
                      <p>新增用户、训练场次和报告产出放在同一张图里看转化闭环。</p>
                    </div>
                  </div>
                  {dashboardChartOptions ? <AdminChart option={dashboardChartOptions.trend} height={320} /> : <p className={adminClasses("admin2-empty")}>暂无趋势数据。</p>}
                </section>

                <section className={adminClasses("admin2-panel")}>
                  <div className={adminClasses("admin2-panel-head")}>
                    <div>
                      <h3>高频使用用户</h3>
                      <p>优先回访高使用、高余额或最近活跃用户。</p>
                    </div>
                  </div>
                  <div className={adminClasses("admin2-table admin2-table--compact")}>
                    {dashboardStats?.top_users.length ? dashboardStats.top_users.map((user) => (
                      <button type="button" className={adminClasses("admin2-table-row")} key={user.email} onClick={() => void loadUserHistory(user.email, dashboardUserToSnapshot(user))}>
                        <span><strong>{user.email}</strong><em>{user.last_interview_at ? formatDateTime(user.last_interview_at) : "暂无训练"}</em></span>
                        <span>{user.completed_interviews}/{user.total_interviews}</span>
                        <span>{user.credit_balance} 次</span>
                      </button>
                    )) : <p className={adminClasses("admin2-empty")}>暂无训练记录。</p>}
                  </div>
                </section>
              </div>
            </section>
          )}

          {activeAdminSection === "users" && (
            <section className={adminClasses("admin2-section")}>
              <div className={adminClasses("admin2-toolbar")}>
                <form className={adminClasses("admin2-search")} onSubmit={searchUsers}>
                  <AppIcon icon="lucide:search" size={18} />
                  <input
                    type="search"
                    value={userSearchQuery}
                    onChange={(event) => setUserSearchQuery(event.target.value)}
                    placeholder="搜索邮箱或关键词；留空查看最近用户"
                  />
                  <button type="submit" className={adminClasses("admin2-primary-button")}>搜索</button>
                  <button
                    type="button"
                    className={adminClasses("admin2-secondary-button")}
                    onClick={() => {
                      setUserSearchQuery("");
                      void loadUsers("", true, 0);
                    }}
                  >
                    全部用户
                  </button>
                </form>
                <div className={adminClasses("admin2-summary-strip")}>
                  <span><b>{userListSummary.total}</b>{userListTotalIsEstimated ? "匹配以上" : "匹配用户"}</span>
                  <span><b>{userListSummary.active}</b>当前页启用</span>
                  <span><b>{userListSummary.disabled}</b>当前页停用</span>
                  <span><b>{userListSummary.creditBalance}</b>当前页余额</span>
                </div>
              </div>

              <div className={adminClasses("admin2-master-detail")}>
                <section className={adminClasses("admin2-panel admin2-panel--list")}>
                  <div className={adminClasses("admin2-panel-head admin2-panel-head--row")}>
                    <div>
                      <h3>{userSearchQuery.trim() ? "搜索结果" : "最近用户"}</h3>
                      <p>{isUserListLoading ? "正在同步用户列表" : `${userListRange} · 当前页 ${userSearchResults.length} 个用户 · ${userListSummary.interviews} 场训练`}</p>
                    </div>
                    <div className={adminClasses("admin2-pagination")}>
                      <button type="button" disabled={userListOffset === 0 || isUserListLoading} onClick={() => void loadUsers(userSearchQuery.trim(), true, Math.max(0, userListOffset - ADMIN_USER_PAGE_SIZE))}>上一页</button>
                      <button type="button" disabled={!userListHasMore || isUserListLoading} onClick={() => void loadUsers(userSearchQuery.trim(), true, userListOffset + ADMIN_USER_PAGE_SIZE)}>下一页</button>
                    </div>
                  </div>
                  <div className={adminClasses("admin2-data-table")}>
                    <div className={adminClasses("admin2-data-head")}>
                      <span>用户</span>
                      <span>状态</span>
                      <span>余额</span>
                      <span>训练</span>
                      <span>最近活跃</span>
                      <span>操作</span>
                    </div>
                    {isUserListLoading && <p className={adminClasses("admin2-empty")}>正在读取用户列表。</p>}
                    {!isUserListLoading && userSearchResults.length === 0 && <p className={adminClasses("admin2-empty")}>暂无用户记录，或当前关键词没有匹配用户。</p>}
                    {!isUserListLoading && userSearchResults.map((user) => (
                      <article className={adminClasses("admin2-data-row", selectedUserEmail === user.email && "is-selected")} key={user.email}>
                        <button type="button" className={adminClasses("admin2-data-main")} onClick={() => void loadUserHistory(user.email, user)}>
                          <span><strong>{user.email}</strong><em>{businessLabel(user.role)}</em></span>
                          <StatusChip tone={user.is_active ? "good" : "danger"}>{user.is_active ? "启用中" : "已停用"}</StatusChip>
                          <b>{user.credit_balance} 次</b>
                          <span>{user.completed_interviews}/{user.total_interviews}</span>
                          <span>{user.last_interview_at ? formatDateTime(user.last_interview_at) : "暂无"}</span>
                        </button>
                        <div className={adminClasses("admin2-row-actions")}>
                          <button
                            type="button"
                            disabled={isLocked(`user-status:${user.email}`)}
                            onClick={() => void updateUserStatus(user, !user.is_active)}
                          >
                            {isLocked(`user-status:${user.email}`) ? "处理中" : user.is_active ? "禁用" : "启用"}
                          </button>
                          <button
                            type="button"
                            disabled={isLocked(`user-role:${user.email}`)}
                            onClick={() => void updateUserRole(user, user.role === "admin" ? "user" : "admin")}
                          >
                            {isLocked(`user-role:${user.email}`) ? "处理中" : user.role === "admin" ? "撤销管理员" : "设为管理员"}
                          </button>
                        </div>
                      </article>
                    ))}
                  </div>
                </section>
                {renderUserDrawer()}
              </div>
            </section>
          )}

          {activeAdminSection === "credits" && (
            <section className={adminClasses("admin2-section")}>
              <div className={adminClasses("admin2-tabs")}>
                <nav className={adminClasses("admin2-tab-nav")} aria-label="权益售后子功能">
                  <button
                    type="button"
                    className={adminClasses("admin2-tab-button", creditsSubTab === "adjust" && "is-active")}
                    onClick={() => setCreditsSubTab("adjust")}
                  >
                    积分操作
                  </button>
                  <button
                    type="button"
                    className={adminClasses("admin2-tab-button", creditsSubTab === "refunds" && "is-active")}
                    onClick={() => setCreditsSubTab("refunds")}
                  >
                    退款工单
                    {refundCases.filter((entry) => entry.status === "open" || entry.status === "processing").length > 0 && (
                      <span className={adminClasses("admin2-tab-badge")}>
                        {refundCases.filter((entry) => entry.status === "open" || entry.status === "processing").length}
                      </span>
                    )}
                  </button>
                </nav>

                {creditsSubTab === "adjust" && (
                  <div className={adminClasses("admin2-grid admin2-grid--ops")}>
                    <form className={adminClasses("admin2-panel admin2-form")} onSubmit={submitCreditGrant}>
                      <div className={adminClasses("admin2-panel-head")}>
                        <div>
                          <h3>次数调整</h3>
                          <p>用于人工开通、售后补偿、退款退回和余额修正。</p>
                        </div>
                      </div>
                      <label>用户邮箱<input type="email" value={creditUser} onChange={(event) => setCreditUser(event.target.value)} placeholder="user@example.com" required /></label>
                      <label>次数变化<input type="number" value={creditAmount} onChange={(event) => setCreditAmount(event.target.value)} required /></label>
                      <div className={adminClasses("admin2-field")}>
                        <span>调整原因</span>
                        <AdminSelect ariaLabel="选择次数调整原因" value={creditReason} options={creditReasonOptions} onChange={setCreditReason} />
                        <small>{selectedCreditReason.help}</small>
                      </div>
                      <label>处理备注<input value={creditNote} onChange={(event) => setCreditNote(event.target.value)} placeholder="例如：微信沟通后补发 1 次" maxLength={240} /></label>
                      <div className={adminClasses("admin2-actions")}>
                        <button type="submit" className={adminClasses("admin2-primary-button")} disabled={isLocked("credit-adjust")}>
                          {isLocked("credit-adjust") ? "提交中" : "提交调整"}
                        </button>
                        <button type="button" className={adminClasses("admin2-secondary-button")} onClick={() => loadCreditLedger()}>查看流水</button>
                      </div>
                    </form>

                    <form className={adminClasses("admin2-panel admin2-form")} onSubmit={submitVoucherIssue}>
                      <div className={adminClasses("admin2-panel-head")}>
                        <div>
                          <h3>体验券发放</h3>
                          <p>适合内测激励、客服补偿和活动批次，不改变次数余额。</p>
                        </div>
                      </div>
                      <label className={adminClasses("admin2-check")}><input type="checkbox" checked={voucherAllUsers} onChange={(event) => setVoucherAllUsers(event.target.checked)} />发放给全部启用中的普通用户</label>
                      <label>用户邮箱<textarea value={voucherEmails} onChange={(event) => setVoucherEmails(event.target.value)} placeholder="多个邮箱可用换行、空格或逗号分隔" disabled={voucherAllUsers} rows={4} /></label>
                      <label>每人发放张数<input type="number" min={1} max={20} value={voucherQuantity} onChange={(event) => setVoucherQuantity(event.target.value)} required /></label>
                      <div className={adminClasses("admin2-field")}>
                        <span>发放原因</span>
                        <AdminSelect ariaLabel="选择体验券发放原因" value={voucherReason} options={voucherReasonOptions} onChange={setVoucherReason} />
                        <small>{selectedVoucherReason.help}</small>
                      </div>
                      <label>处理备注<input value={voucherNote} onChange={(event) => setVoucherNote(event.target.value)} placeholder="例如：首批内测用户体验券" maxLength={240} /></label>
                      <button type="submit" className={adminClasses("admin2-primary-button")} disabled={isLocked("voucher-issue")}>
                        {isLocked("voucher-issue") ? "发放中" : "发放体验券"}
                      </button>
                    </form>
                  </div>
                )}

                {creditsSubTab === "refunds" && (
                  <div className={adminClasses("admin2-grid admin2-grid--ops")}>
                    <section className={adminClasses("admin2-panel")}>
                      <div className={adminClasses("admin2-panel-head")}>
                        <div>
                          <h3>退款工单列表</h3>
                          <p>待处理工单需优先核查训练报告、积分流水和沟通记录。</p>
                        </div>
                      </div>
                      {refundCases.length === 0 ? (
                        <p className={adminClasses("admin2-empty")}>暂无退款纠纷记录。</p>
                      ) : (
                        <div className={adminClasses("admin2-refund-table-panel")}>
                          <div className={adminClasses("admin2-refund-head")}>
                            <span>用户</span>
                            <span>原因</span>
                            <span>金额</span>
                            <span>次数</span>
                            <span>状态</span>
                            <span>操作</span>
                          </div>
                          {refundCases.map((entry) => (
                            <div className={adminClasses("admin2-refund-row")} key={entry.id}>
                              <div>
                                <strong style={{ display: "block", fontSize: "13px", fontWeight: 700, color: "#0f172a" }}>{entry.user_email}</strong>

                                <span style={{ display: "block", fontSize: "11px", color: "#94a3b8" }}>{formatDateTime(entry.created_at)}</span>
                              </div>
                              <span style={{ fontSize: "12px", color: "#475569" }}>{businessLabel(entry.reason)}</span>
                              <span style={{ fontSize: "13px", fontWeight: 680 }}>{formatCents(entry.amount_cents, entry.currency)}</span>
                              <span style={{ fontSize: "13px", color: "#475569" }}>{entry.credit_adjustment ?? 0}</span>
                              <StatusChip tone={entry.status === "resolved" ? "good" : entry.status === "rejected" ? "neutral" : "warning"}>
                                {businessLabel(entry.status)}
                              </StatusChip>
                              <div className={adminClasses("admin2-row-actions")}>
                                <button type="button" disabled={isLocked(`refund-status:${entry.id}`)} onClick={() => void updateRefundCaseStatus(entry, "processing")}>处理中</button>
                                <button type="button" disabled={isLocked(`refund-status:${entry.id}`)} onClick={() => void updateRefundCaseStatus(entry, "resolved")}>解决</button>
                                <button type="button" disabled={isLocked(`refund-status:${entry.id}`)} onClick={() => void updateRefundCaseStatus(entry, "rejected")}>驳回</button>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </section>

                    <section className={adminClasses("admin2-panel admin2-form")}>
                      <div className={adminClasses("admin2-panel-head")}>
                        <div>
                          <h3>客服备注</h3>
                          <p>{selectedUserEmail ? `当前用户：${selectedUserEmail}` : "先在用户中心选择用户，再写入售后备注。"}</p>
                        </div>
                      </div>
                      <form className={adminClasses("admin2-nested-form")} onSubmit={submitCustomerServiceNote}>
                        <div className={adminClasses("admin2-field")}>
                          <span>备注类型</span>
                          <AdminSelect ariaLabel="选择客服备注类型" value={noteCategory} options={noteCategoryOptions} onChange={setNoteCategory} />
                        </div>
                        <label>关联训练 ID<input value={noteSessionId} onChange={(event) => setNoteSessionId(event.target.value)} placeholder="可选，复制 session_id" /></label>
                        <label>沟通内容<textarea value={noteContent} onChange={(event) => setNoteContent(event.target.value)} placeholder="记录用户来源、沟通结论、补偿口径或后续跟进点" rows={4} /></label>
                        <button
                          type="submit"
                          className={adminClasses("admin2-primary-button")}
                          disabled={Boolean(selectedUserEmail && isLocked(`note-create:${selectedUserEmail}`))}
                        >
                          {selectedUserEmail && isLocked(`note-create:${selectedUserEmail}`) ? "保存中" : "保存备注"}
                        </button>
                      </form>

                      <div className={adminClasses("admin2-panel-head")} style={{ marginTop: "8px" }}>
                        <div>
                          <h3>新建退款纠纷</h3>
                          <p>{selectedUserEmail ? "创建后进入审计与售后队列。" : "先在用户中心选择用户。"}</p>
                        </div>
                      </div>
                      <aside className={adminClasses("admin2-accounting-boundary")} role="note" aria-label="退款工单账务边界">
                        <strong>账务边界</strong>
                        <ul>
                          {REFUND_ACCOUNTING_BOUNDARY_ITEMS.map((item) => <li key={item}>{item}</li>)}
                        </ul>
                      </aside>
                      <form className={adminClasses("admin2-nested-form")} onSubmit={submitRefundCase}>
                        <div className={adminClasses("admin2-field")}>
                          <span>原因</span>
                          <AdminSelect ariaLabel="选择退款纠纷原因" value={refundReason} options={refundReasonOptions} onChange={setRefundReason} />
                        </div>
                        <div className={adminClasses("admin2-form-grid")}>
                          <label>退款金额<input value={refundAmountYuan} onChange={(event) => setRefundAmountYuan(event.target.value)} placeholder="例如 19.90，可空" inputMode="decimal" /></label>
                          <label>{REFUND_CREDIT_FIELD_LABEL}<input value={refundCreditAdjustment} onChange={(event) => setRefundCreditAdjustment(event.target.value)} placeholder="例如 1，可空" inputMode="numeric" /></label>
                        </div>
                        <label>关联训练 ID<input value={refundSessionId} onChange={(event) => setRefundSessionId(event.target.value)} placeholder="可选" /></label>
                        <label>纠纷描述<textarea value={refundDescription} onChange={(event) => setRefundDescription(event.target.value)} placeholder="记录用户诉求、核对依据和处理口径" rows={4} /></label>
                        <button
                          type="submit"
                          className={adminClasses("admin2-primary-button")}
                          disabled={Boolean(selectedUserEmail && isLocked(`refund-create:${selectedUserEmail}`))}
                        >
                          {selectedUserEmail && isLocked(`refund-create:${selectedUserEmail}`) ? "创建中" : "创建纠纷记录"}
                        </button>
                      </form>
                    </section>
                  </div>
                )}
              </div>
            </section>
          )}

          {activeAdminSection === "quality" && (
            <section className={adminClasses("admin2-section")}>
              <div className={adminClasses("admin2-metrics-grid admin2-metrics-grid--four")}>
                <MetricCard label="完成训练" value={dashboardStats?.overview.completed_sessions ?? "暂无"} detail={`总场次 ${dashboardStats?.overview.total_sessions ?? 0}`} icon="lucide:circle-check-big" tone="good" />
                <MetricCard label="报告产出" value={dashboardStats?.overview.total_reports ?? "暂无"} detail={`平均分 ${dashboardStats?.overview.average_report_score ?? "暂无"}`} icon="lucide:file-check-2" />
                <MetricCard label="进行中" value={dashboardStats?.overview.active_sessions ?? "暂无"} detail="可关注异常中断与恢复" icon="lucide:activity" tone="warning" />
                <MetricCard label="核心状态" value={interviewCoreHealth?.ready ? "正常" : "待处理"} detail={interviewCoreHealthMessage} icon="lucide:shield-check" tone={interviewCoreHealth?.ready ? "good" : "danger"} />
              </div>
              <div className={adminClasses("admin2-grid admin2-grid--quality")}>
                <section className={adminClasses("admin2-panel admin2-panel--wide")}>
                  <div className={adminClasses("admin2-panel-head")}>
                    <div>
                      <h3>训练与报告趋势</h3>
                      <p>判断训练是否完成、报告是否稳定产出。</p>
                    </div>
                  </div>
                  {dashboardChartOptions ? <AdminChart option={dashboardChartOptions.trend} height={320} /> : <p className={adminClasses("admin2-empty")}>暂无趋势数据。</p>}
                </section>
                <section className={adminClasses("admin2-panel")}>
                  <div className={adminClasses("admin2-panel-head")}><h3>训练模块占比</h3></div>
                  {dashboardChartOptions ? <AdminChart option={dashboardChartOptions.moduleMix} /> : <p className={adminClasses("admin2-empty")}>暂无模块数据。</p>}
                </section>
                <section className={adminClasses("admin2-panel")}>
                  <div className={adminClasses("admin2-panel-head")}><h3>会话状态</h3></div>
                  {dashboardChartOptions ? <AdminChart option={dashboardChartOptions.sessionStatus} /> : <p className={adminClasses("admin2-empty")}>暂无状态数据。</p>}
                </section>
                <section className={adminClasses("admin2-panel admin2-panel--wide")}>
                  <div className={adminClasses("admin2-panel-head")}>
                    <div>
                      <h3>面试核心 readiness</h3>
                      <p>能力卡片、向量覆盖和召回探针是正式训练质量的底座。</p>
                    </div>
                  </div>
                  {!interviewCoreHealth ? (
                    <p className={adminClasses("admin2-empty")}>{interviewCoreHealthMessage}</p>
                  ) : (
                    <div className={adminClasses("admin2-core-grid")}>
                      <article>
                        <span>能力卡片</span>
                        <strong>{formatCoreCount(interviewCoreHealth.capability_cards.total_seed_count)}</strong>
                        <StatusChip tone={interviewCoreHealth.capability_cards.ready ? "good" : "danger"}>{interviewCoreHealth.capability_cards.ready ? "正常" : "退化"}</StatusChip>
                      </article>
                      <article>
                        <span>向量覆盖</span>
                        <strong>{formatCorePercent(interviewCoreHealth.capability_vectors.coverage_rate)}</strong>
                        <p>{formatCoreCount(interviewCoreHealth.capability_vectors.non_empty_vector_count)} 条非空向量</p>
                      </article>
                      <article>
                        <span>召回探针</span>
                        <strong>{interviewCoreHealth.recall_quality.passed_probe_count}/{interviewCoreHealth.recall_quality.probe_count}</strong>
                        <p>{interviewCoreHealth.failure_reasons.length > 0 ? interviewCoreHealth.failure_reasons.slice(0, 2).join(" / ") : interviewCoreHealth.failure_summary}</p>
                      </article>
                    </div>
                  )}
                </section>
              </div>
            </section>
          )}

          {activeAdminSection === "ai" && (
            <section className={adminClasses("admin2-section")}>
              <div className={adminClasses("admin2-grid admin2-grid--ai")}>
                <section className={adminClasses("admin2-panel admin2-panel--wide")}>
                  <div className={adminClasses("admin2-panel-head")}>
                    <div>
                      <h3>AI Worker 任务中心</h3>
                      <p>只展示任务元数据与稳定错误码，不暴露简历、回答或模型提示词。</p>
                    </div>
                  </div>
                  <AdminTaskMonitor
                    tasks={aiTasks}
                    loading={areAiTasksLoading}
                    isRetrying={(task) => isLocked(`task-retry:${task.id}`)}
                    onRetry={(task) => void retryAiTask(task)}
                  />
                </section>
                <section className={adminClasses("admin2-panel admin2-panel--wide")}>
                  <div className={adminClasses("admin2-panel-head")}>
                    <div>
                      <h3>供应商路由</h3>
                      <p>仅管理路由元数据；真实密钥由服务器 secret 管理，不通过网页录入。</p>
                    </div>
                  </div>
                  <div className={adminClasses("admin2-provider-list")}>
                    {providers.length === 0 && <p className={adminClasses("admin2-empty")}>暂无 AI 服务配置。</p>}
                    {providers.map((provider) => (
                      <article className={adminClasses("admin2-provider-row")} key={provider.id}>
                        <div>
                          <strong>{provider.id}</strong>
                          <span>{provider.provider_name} / {provider.model_name}</span>
                          <em>{businessLabel(provider.provider_type)} · {businessLabel(provider.purpose)} · {provider.region} · 优先级 {provider.priority}</em>
                        </div>
                        <StatusChip tone={provider.enabled ? "good" : "neutral"}>{provider.enabled ? "启用中" : "已停用"}</StatusChip>
                        <span>{provider.has_api_key ? `密钥：${provider.api_key_preview}` : "密钥：未配置"}</span>
                        <div className={adminClasses("admin2-row-actions")}>
                          <button
                            type="button"
                            disabled={isLocked(`provider-test:${provider.id}`)}
                            onClick={() => void testProvider(provider)}
                          >
                            {isLocked(`provider-test:${provider.id}`) ? "校验中" : "校验配置"}
                          </button>
                          <button
                            type="button"
                            disabled={isLocked(`provider-toggle:${provider.id}`)}
                            onClick={() => void toggleProvider(provider)}
                          >
                            {isLocked(`provider-toggle:${provider.id}`) ? "处理中" : provider.enabled ? "停用" : "启用"}
                          </button>
                        </div>
                        {providerTestResults[provider.id] && <p>{providerTestResults[provider.id]}</p>}
                      </article>
                    ))}
                  </div>
                </section>
                <section className={adminClasses("admin2-panel")}>
                  <div className={adminClasses("admin2-panel-head")}><h3>AI 调用质量</h3></div>
                  {dashboardChartOptions ? <AdminChart option={dashboardChartOptions.aiQuality} /> : <p className={adminClasses("admin2-empty")}>暂无调用分布。</p>}
                </section>
                <section className={adminClasses("admin2-panel admin2-panel--wide")}>
                  <div className={adminClasses("admin2-panel-head")}>
                    <div>
                      <h3>最近模型调用</h3>
                      <p>失败、延迟、token、成本和音频时长用于定位服务退化。</p>
                    </div>
                    <div className={adminClasses("admin2-log-filter-bar")}>
                      {(["all", "success", "failed"] as const).map((f) => (
                        <button key={f} type="button" className={adminClasses("admin2-log-filter-btn", aiLogFilter === f && "is-active")} onClick={() => setAiLogFilter(f)}>
                          {f === "all" ? "全部" : f === "success" ? "成功" : "失败"}
                        </button>
                      ))}
                    </div>
                  </div>
                  <div className={adminClasses("admin2-log-list")}>
                    {aiCallLogs.length === 0 && <p className={adminClasses("admin2-empty")}>暂无模型调用记录。</p>}
                    {aiCallLogs
                      .filter((e) => aiLogFilter === "all" || (aiLogFilter === "success" ? e.success : !e.success))
                      .slice(0, 20)
                      .map((entry) => (
                        <article className={adminClasses("admin2-log-row", entry.success ? "admin2-log-row--success" : "admin2-log-row--danger")} key={entry.id}>
                          <div className={adminClasses("admin2-log-main")}>
                            <strong>{entry.provider_name} / {entry.model_name}</strong>
                            <span>{businessLabel(entry.purpose)} · {entry.session_id ?? "无会话"}{entry.provider_request_id ? ` · ${entry.provider_request_id}` : ""}</span>
                          </div>
                          <StatusChip tone={entry.success ? "good" : "danger"}>
                            {entry.success ? "成功" : getApiErrorMessage({ detail: entry.error_code ?? undefined }, "调用失败")}
                          </StatusChip>
                          <div className={adminClasses("admin2-log-meta")}>
                            <span>{entry.latency_ms != null ? `${entry.latency_ms} ms` : "—"}</span>
                            <span>{entry.input_tokens ?? 0}/{entry.output_tokens ?? 0} tok</span>
                            <time className={adminClasses("admin2-log-time")}>{formatDateTime(entry.created_at)}</time>
                          </div>
                        </article>
                      ))}
                  </div>
                </section>
              </div>
            </section>
          )}

          {activeAdminSection === "audit" && (
            <section className={adminClasses("admin2-section")}>
              <div className={adminClasses("admin2-metrics-grid admin2-metrics-grid--four")}>
                <MetricCard label="管理员操作" value={auditLogs.length} detail="最近审计日志" icon="lucide:shield-check" />
                <MetricCard label="登录失败" value={authLoginLogs.filter((entry) => !entry.success).length} detail="最近认证日志" icon="lucide:key-round" tone="warning" />
                <MetricCard label="内容安全" value={contentSafetyLogs.length} detail="风险观察记录" icon="lucide:shield-ban" tone="warning" />
                <MetricCard label="售后纠纷" value={refundCases.filter((entry) => entry.status !== "resolved").length} detail="未解决/处理中" icon="lucide:receipt-text" tone="warning" />
              </div>

              <div className={adminClasses("admin2-tabs")}>
                <nav className={adminClasses("admin2-tab-nav")} aria-label="风控审计子功能">
                  <button
                    type="button"
                    className={adminClasses("admin2-tab-button", auditSubTab === "login" && "is-active")}
                    onClick={() => setAuditSubTab("login")}
                  >
                    登录日志
                    {authLoginLogs.filter((e) => !e.success).length > 0 && (
                      <span className={adminClasses("admin2-tab-badge")}>
                        {authLoginLogs.filter((e) => !e.success).length}
                      </span>
                    )}
                  </button>
                  <button
                    type="button"
                    className={adminClasses("admin2-tab-button", auditSubTab === "safety" && "is-active")}
                    onClick={() => setAuditSubTab("safety")}
                  >
                    内容安全
                    {contentSafetyLogs.filter((e) => e.risk_level === "high").length > 0 && (
                      <span className={adminClasses("admin2-tab-badge")}>
                        {contentSafetyLogs.filter((e) => e.risk_level === "high").length}
                      </span>
                    )}
                  </button>
                  <button
                    type="button"
                    className={adminClasses("admin2-tab-button", auditSubTab === "operations" && "is-active")}
                    onClick={() => setAuditSubTab("operations")}
                  >
                    管理操作
                    <span className={adminClasses("admin2-tab-badge admin2-tab-badge--neutral")}>{auditLogs.length}</span>
                  </button>
                </nav>

                {auditSubTab === "login" && (
                  <section className={adminClasses("admin2-audit-tab-panel")}>
                    <div className={adminClasses("admin2-panel-head")}>
                      <div>
                        <h3>登录日志</h3>
                        <p>关注异常 IP、验证码失败和管理员账号尝试。</p>
                      </div>
                      <div className={adminClasses("admin2-log-filter-bar")}>
                        {(["all", "success", "failed"] as const).map((f) => (
                          <button key={f} type="button" className={adminClasses("admin2-log-filter-btn", loginLogFilter === f && "is-active")} onClick={() => setLoginLogFilter(f)}>
                            {f === "all" ? "全部" : f === "success" ? "成功" : "失败"}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div className={adminClasses("admin2-audit-filter")}>
                      <AppIcon icon="lucide:search" size={16} />
                      <input
                        type="search"
                        value={auditLoginFilter}
                        onChange={(e) => setAuditLoginFilter(e.target.value)}
                        placeholder="按邮箱筛选..."
                      />
                      {auditLoginFilter && (
                        <button type="button" className={adminClasses("admin2-secondary-button")} onClick={() => setAuditLoginFilter("")}>
                          清除
                        </button>
                      )}
                    </div>
                    <div className={adminClasses("admin2-log-list")}>
                      {authLoginLogs.length === 0 && <p className={adminClasses("admin2-empty")}>暂无登录日志。</p>}
                      {authLoginLogs
                        .filter((e) => {
                          if (auditLoginFilter && !e.email.includes(auditLoginFilter)) return false;
                          if (loginLogFilter === "success") return e.success;
                          if (loginLogFilter === "failed") return !e.success;
                          return true;
                        })
                        .map((entry) => (
                          <article className={adminClasses("admin2-log-row", entry.success ? "admin2-log-row--success" : "admin2-log-row--danger")} key={entry.id}>
                            <div className={adminClasses("admin2-log-main")}>
                              <strong>{entry.email}</strong>
                              <span>{businessLabel(entry.auth_method)} · {businessLabel(entry.role)} · {entry.ip_address ?? "未知 IP"}</span>
                            </div>
                            <StatusChip tone={entry.success ? "good" : "danger"}>
                              {entry.success ? "成功" : getApiErrorMessage({ detail: entry.failure_reason ?? undefined }, "失败")}
                            </StatusChip>
                            <time className={adminClasses("admin2-log-time")}>{formatDateTime(entry.created_at)}</time>
                          </article>
                        ))}
                    </div>
                  </section>
                )}

                {auditSubTab === "safety" && (
                  <section className={adminClasses("admin2-audit-tab-panel")}>
                    <div className={adminClasses("admin2-panel-head")}>
                      <div>
                        <h3>内容安全</h3>
                        <p>复核高风险输入来源、命中类别和用户会话。</p>
                      </div>
                      <div className={adminClasses("admin2-log-filter-bar")}>
                        {(["all", "high", "medium", "low"] as const).map((f) => (
                          <button key={f} type="button" className={adminClasses("admin2-log-filter-btn", safetyLogFilter === f && "is-active", f === "high" && "admin2-log-filter-btn--danger", f === "medium" && "admin2-log-filter-btn--warning")} onClick={() => setSafetyLogFilter(f)}>
                            {f === "all" ? "全部" : f === "high" ? "高风险" : f === "medium" ? "中风险" : "低风险"}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div className={adminClasses("admin2-log-list")}>
                      {contentSafetyLogs.length === 0 && <p className={adminClasses("admin2-empty")}>暂无内容安全记录。</p>}
                      {contentSafetyLogs
                        .filter((e) => safetyLogFilter === "all" || e.risk_level === safetyLogFilter)
                        .map((entry) => (
                          <article className={adminClasses("admin2-log-row", entry.risk_level === "high" ? "admin2-log-row--danger" : "admin2-log-row--warning")} key={entry.id}>
                            <div className={adminClasses("admin2-log-main")}>
                              <strong>{entry.user_email ?? "未知用户"} · {entry.source}</strong>
                              <span>{entry.session_id ?? "无会话"} · {entry.categories.map((item) => businessLabel(item)).join(" / ")}</span>
                              {entry.content_excerpt && <em className={adminClasses("admin2-log-excerpt")}>{entry.content_excerpt}</em>}
                            </div>
                            <StatusChip tone={entry.risk_level === "high" ? "danger" : "warning"}>
                              {businessLabel(entry.action)} / {businessLabel(entry.risk_level)}
                            </StatusChip>
                            <time className={adminClasses("admin2-log-time")}>{formatDateTime(entry.created_at)}</time>
                          </article>
                        ))}
                    </div>
                  </section>
                )}

                {auditSubTab === "operations" && (
                  <div className={adminClasses("admin2-grid admin2-grid--audit")}>
                    <section className={adminClasses("admin2-panel")}>
                      <div className={adminClasses("admin2-panel-head")}><h3>管理操作日志</h3></div>
                      <div className={adminClasses("admin2-log-list")}>
                        {auditLogs.length === 0 && <p className={adminClasses("admin2-empty")}>暂无后台操作记录。</p>}
                        {auditLogs.slice(0, 20).map((entry) => (
                          <article className={adminClasses("admin2-log-row")} key={entry.id}>
                            <div>
                              <strong>{businessLabel(entry.action)}</strong>
                              <span>{entry.admin_email} · {businessLabel(entry.target_type)}</span>
                            </div>
                            <span style={{ fontSize: "12px", color: "#64748b", wordBreak: "break-all" }}>{entry.target_id}</span>
                            <small>{formatDateTime(entry.created_at)}</small>
                          </article>
                        ))}
                      </div>
                    </section>
                    <section className={adminClasses("admin2-panel")}>
                      <div className={adminClasses("admin2-panel-head")}><h3>退款纠纷（审计视图）</h3></div>
                      <div className={adminClasses("admin2-log-list")}>
                        {refundCases.length === 0 && <p className={adminClasses("admin2-empty")}>暂无退款纠纷。</p>}
                        {refundCases.slice(0, 14).map((entry) => (
                          <article className={adminClasses("admin2-log-row")} key={entry.id}>
                            <div>
                              <strong>{entry.user_email}</strong>
                              <span>
                                {businessLabel(entry.reason)} · {formatCents(entry.amount_cents, entry.currency)} · {REFUND_CREDIT_FIELD_LABEL}：{entry.credit_adjustment ?? 0}
                              </span>
                            </div>
                            <StatusChip tone={entry.status === "resolved" ? "good" : entry.status === "rejected" ? "neutral" : "warning"}>{businessLabel(entry.status)}</StatusChip>
                            <div className={adminClasses("admin2-row-actions")}>
                              <button type="button" disabled={isLocked(`refund-status:${entry.id}`)} onClick={() => void updateRefundCaseStatus(entry, "processing")}>处理中</button>
                              <button type="button" disabled={isLocked(`refund-status:${entry.id}`)} onClick={() => void updateRefundCaseStatus(entry, "resolved")}>已解决</button>
                              <button type="button" disabled={isLocked(`refund-status:${entry.id}`)} onClick={() => void updateRefundCaseStatus(entry, "rejected")}>驳回</button>
                            </div>
                          </article>
                        ))}
                      </div>
                    </section>
                  </div>
                )}
              </div>
            </section>
          )}

          {activeAdminSection === "system" && (
            <section className={adminClasses("admin2-section")}>
              <div className={adminClasses("admin2-grid admin2-grid--system")}>
                {groupedSystemConfigs.length === 0 && <p className={adminClasses("admin2-empty")}>暂无系统配置项。</p>}
                {groupedSystemConfigs.map(([group, configs]) => (
                  <section className={adminClasses("admin2-panel")} key={group}>
                    <div className={adminClasses("admin2-panel-head")}>
                      <div>
                        <h3>{group}</h3>
                        <p>{configs.length} 个配置项，保存后写入后台审计日志。</p>
                      </div>
                    </div>
                    <div className={adminClasses("admin2-config-list")}>
                      {configs.map((config) => (
                        <form
                          className={adminClasses("admin2-config-row")}
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
                            <BooleanConfigSelect config={config} />
                          ) : (
                            <input name="value" defaultValue={configInputValue(config.value)} />
                          )}
                          <button type="submit" className={adminClasses("admin2-primary-button")} disabled={isLocked(`system-config:${config.key}`)}>
                            {isLocked(`system-config:${config.key}`) ? "保存中" : "保存"}
                          </button>
                        </form>
                      ))}
                    </div>
                  </section>
                ))}
              </div>
            </section>
          )}
        </section>
      </div>
    </main>
  );
}
