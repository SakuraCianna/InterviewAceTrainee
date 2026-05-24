import { FormEvent, useEffect, useMemo, useState } from "react";
import { AppIcon } from "../../components/AppIcon";
import { csrfHeaders } from "../../lib/api";

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
  error_message?: string | null;
  created_at: string;
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
  summary: string;
  dimensions: { name: string; score: number; comment: string }[];
  strengths: string[];
  improvements: string[];
  next_plan: string[];
  turns: { round_name: string; question: string; answer: string }[];
};

type SystemConfig = {
  key: string;
  value: boolean | number | string | Record<string, unknown> | unknown[] | null;
  description: string;
  updated_at?: string | null;
};

export function AdminShell() {
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [providers, setProviders] = useState<ProviderConfig[]>([]);
  const [auditLogs, setAuditLogs] = useState<AdminAuditLog[]>([]);
  const [creditLedger, setCreditLedger] = useState<CreditLedgerEntry[]>([]);
  const [aiCallLogs, setAiCallLogs] = useState<AICallLogEntry[]>([]);
  const [systemConfigs, setSystemConfigs] = useState<SystemConfig[]>([]);
  const [providerTestResults, setProviderTestResults] = useState<Record<string, string>>({});
  const [userSearchQuery, setUserSearchQuery] = useState("");
  const [userSearchResults, setUserSearchResults] = useState<AdminUserSearchItem[]>([]);
  const [selectedUserHistory, setSelectedUserHistory] = useState<AdminInterviewHistoryItem[]>([]);
  const [selectedUserEmail, setSelectedUserEmail] = useState("");
  const [loginEmail, setLoginEmail] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [loginCode, setLoginCode] = useState("");
  const [creditUser, setCreditUser] = useState("");
  const [creditAmount, setCreditAmount] = useState("1");
  const [creditReason, setCreditReason] = useState("manual_grant");
  const [creditNote, setCreditNote] = useState("");
  const [selectedReport, setSelectedReport] = useState<AdminInterviewReport | null>(null);
  const [reportMessage, setReportMessage] = useState("");
  const [message, setMessage] = useState("正在检查管理员会话。");
  const [isLoading, setIsLoading] = useState(true);

  const enabledProviderCount = useMemo(() => providers.filter((provider) => provider.enabled).length, [providers]);

  useEffect(() => {
    void loadCurrentUser();
  }, []);

  async function loadCurrentUser() {
    setIsLoading(true);
    const response = await fetch("/api/auth/me", { credentials: "include" });
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
      setMessage("当前账户不是管理员，请使用后台白名单邮箱登录。");
      return;
    }

    setCurrentUser(user);
    setIsLoading(false);
    setMessage(`已进入后台：${user.email}`);
    await Promise.all([loadProviders(), loadSystemConfigs(), loadAuditLogs(), loadAiCallLogs()]);
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

  async function loadSystemConfigs() {
    const response = await fetch("/api/admin/system-configs", { credentials: "include" });
    if (!response.ok) {
      return;
    }
    setSystemConfigs((await response.json()) as SystemConfig[]);
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
    await loadCreditLedger(userEmail);
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

    const response = await fetch("/api/auth/email-code/request", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: loginEmail }),
    });
    const data = (await response.json()) as AdminLoginResponse;
    if (!response.ok) {
      setMessage(data.detail ? `验证码发送失败：${data.detail}` : "验证码发送失败。");
      return;
    }

    setLoginCode(data.dev_code ?? "");
    setMessage(data.dev_code ? `开发验证码：${data.dev_code}` : "验证码已发送，请查看邮箱。");
  }

  async function submitAdminLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const response = await fetch("/api/auth/admin/login", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: loginEmail, password: loginPassword, code: loginCode }),
    });
    const data = (await response.json()) as AdminLoginResponse;
    if (!response.ok || !data.access_token) {
      setMessage(data.detail ? `后台登录失败：${data.detail}` : "后台登录失败，请检查邮箱、密码和验证码。");
      return;
    }

    await loadCurrentUser();
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
      setMessage(data.detail ? `次数调整失败：${data.detail}` : "次数调整失败。");
      return;
    }

    setMessage(`${creditUser} 已调整 ${amount} 次，当前余额 ${data.balance_after}。`);
    setCreditAmount("1");
    setCreditReason("manual_grant");
    setCreditNote("");
    await Promise.all([loadAuditLogs(), loadCreditLedger(creditUser)]);
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
    const resultText = response.ok
      ? `${data.success ? "通过" : "未通过"}：${data.detail}`
      : `测试失败：${data.detail ?? "request_failed"}`;
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
      setMessage(data.detail ? `用户状态更新失败：${data.detail}` : "用户状态更新失败。");
      return;
    }
    setMessage(`${user.email} 已${isActive ? "启用" : "禁用"}。`);
    await Promise.all([searchUsers(), loadAuditLogs()]);
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
      setMessage(data.detail ? `系统配置保存失败：${data.detail}` : "系统配置保存失败。");
      return;
    }
    setMessage(`已更新系统配置：${data.key}`);
    await Promise.all([loadSystemConfigs(), loadAuditLogs()]);
  }

  async function refreshAdminData() {
    await Promise.all([loadProviders(), loadSystemConfigs(), loadAuditLogs(), loadAiCallLogs(), loadCreditLedger()]);
  }

  async function logout() {
    await fetch("/api/auth/logout", { method: "POST", credentials: "include", headers: csrfHeaders() });
    setCurrentUser(null);
    setProviders([]);
    setAuditLogs([]);
    setCreditLedger([]);
    setAiCallLogs([]);
    setSystemConfigs([]);
    setProviderTestResults({});
    setUserSearchResults([]);
    setSelectedUserHistory([]);
    setSelectedUserEmail("");
    setSelectedReport(null);
    setReportMessage("");
    setMessage("已退出后台，请重新完成管理员双重认证。");
  }

  return (
    <main className="workspace-page admin-page">
      <header className="workspace-header">
        <a href="/" className="brand-mark">
          <AppIcon icon="solar:soundwave-circle-bold-duotone" size={24} />
          面霸练习生
        </a>
        <div className="workspace-header-actions">
          <span className="session-pill session-pill--admin">{currentUser ? currentUser.email : "Admin Console"}</span>
          {currentUser && (
            <button type="button" className="logout-button" onClick={() => void logout()}>
              <AppIcon icon="lucide:log-out" size={16} />
              退出
            </button>
          )}
        </div>
      </header>

      <section className="admin-hero">
        <span className="eyebrow">Operator Console</span>
        <h1>运营后台</h1>
              <p>后台用于手动发放面试次数、查看 AI 服务状态和检查系统日志。管理员必须在邮箱白名单内，并通过密码与邮箱验证码双重认证。</p>
      </section>

      <div className="admin-status-row">
        <span>{message}</span>
        {currentUser && (
          <button type="button" onClick={refreshAdminData}>
            <AppIcon icon="lucide:activity" size={18} />
            刷新配置
          </button>
        )}
      </div>

      {!currentUser && !isLoading && (
        <form className="admin-login-panel" onSubmit={submitAdminLogin}>
          <h2>管理员登录</h2>
          <label>
            管理员邮箱
            <input type="email" value={loginEmail} onChange={(event) => setLoginEmail(event.target.value)} required />
          </label>
          <label>
            密码
            <input type="password" value={loginPassword} onChange={(event) => setLoginPassword(event.target.value)} minLength={8} required />
          </label>
          <label>
            邮箱验证码
            <div className="admin-code-row">
              <input value={loginCode} onChange={(event) => setLoginCode(event.target.value)} minLength={6} maxLength={6} required />
              <button type="button" onClick={requestAdminCode}>获取验证码</button>
            </div>
          </label>
          <button type="submit" className="admin-primary-button">进入后台</button>
        </form>
      )}

      {currentUser && (
        <>
          <section className="admin-grid admin-grid--compact">
            <article className="admin-card">
              <AppIcon icon="lucide:bot" size={24} />
              <h2>{enabledProviderCount} / {providers.length}</h2>
              <p>启用中的 AI 服务</p>
            </article>
            <article className="admin-card">
              <AppIcon icon="lucide:coins" size={24} />
              <h2>手动开通</h2>
              <p>根据用户沟通结果，手动发放、扣减或补偿面试训练次数。</p>
            </article>
            <article className="admin-card">
              <AppIcon icon="lucide:shield-check" size={24} />
              <h2>白名单后台</h2>
              <p>后台入口仍可隐藏，但真正的权限由服务端校验。</p>
            </article>
          </section>

          <section className="admin-workbench">
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
                <input value={creditReason} onChange={(event) => setCreditReason(event.target.value)} placeholder="manual_grant / refund_adjustment" required />
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
          </section>

          <section className="admin-provider-table admin-user-table">
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
                    <button
                      type="button"
                      className="admin-history-action"
                      onClick={() => void updateUserStatus(user, !user.is_active)}
                    >
                      {user.is_active ? "禁用" : "启用"}
                    </button>
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
                    <div className="admin-report-dimensions">
                      {selectedReport.dimensions.map((dimension) => (
                        <article key={dimension.name}>
                          <span>{dimension.name}</span>
                          <b>{dimension.score}</b>
                          <p>{dimension.comment}</p>
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
                    </div>
                    <div className="admin-report-turns">
                      {selectedReport.turns.map((turn, index) => (
                        <details key={`${turn.round_name}-${index}`}>
                          <summary>{turn.round_name}</summary>
                          <p><b>问：</b>{turn.question}</p>
                          <p><b>答：</b>{turn.answer || "用户未作答"}</p>
                        </details>
                      ))}
                    </div>
                  </>
                )}
              </div>
            )}
          </section>

          <section className="admin-provider-table">
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

          <section className="admin-provider-table">
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

          <section className="admin-provider-table admin-audit-table">
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

          <section className="admin-provider-table admin-audit-table">
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
                    <span>{entry.session_id ?? "no-session"} · {entry.purpose}</span>
                  </div>
                  <em>{entry.success ? "success" : entry.error_message ?? "failed"}</em>
                  <span>{entry.created_at}</span>
                </article>
              ))}
            </div>
          </section>
        </>
      )}
    </main>
  );
}
