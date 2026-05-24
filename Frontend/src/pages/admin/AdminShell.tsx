import { FormEvent, useEffect, useMemo, useState } from "react";
import { AppIcon } from "../../components/AppIcon";

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

const emptyProvider = {
  id: "",
  provider_type: "llm",
  purpose: "general",
  provider_name: "",
  model_name: "",
  priority: "100",
  region: "cn",
};

export function AdminShell() {
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [providers, setProviders] = useState<ProviderConfig[]>([]);
  const [auditLogs, setAuditLogs] = useState<AdminAuditLog[]>([]);
  const [creditLedger, setCreditLedger] = useState<CreditLedgerEntry[]>([]);
  const [aiCallLogs, setAiCallLogs] = useState<AICallLogEntry[]>([]);
  const [loginEmail, setLoginEmail] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [loginCode, setLoginCode] = useState("");
  const [creditUser, setCreditUser] = useState("");
  const [creditAmount, setCreditAmount] = useState("1");
  const [providerForm, setProviderForm] = useState(emptyProvider);
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
    await Promise.all([loadProviders(), loadAuditLogs(), loadAiCallLogs()]);
  }

  async function loadProviders() {
    const response = await fetch("/api/ai-providers", { credentials: "include" });
    if (!response.ok) {
      setMessage("模型配置读取失败。");
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
    if (!creditUser || Number.isNaN(amount) || amount === 0) {
      setMessage("请填写用户邮箱和非 0 次数。");
      return;
    }

    const response = await fetch(`/api/admin/users/${encodeURIComponent(creditUser)}/credits`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ change_amount: amount, reason: amount > 0 ? "manual_grant" : "manual_adjustment" }),
    });
    const data = await response.json();
    if (!response.ok) {
      setMessage(data.detail ? `次数调整失败：${data.detail}` : "次数调整失败。");
      return;
    }

    setMessage(`${creditUser} 已调整 ${amount} 次，当前余额 ${data.balance_after}。`);
    setCreditAmount("1");
    await Promise.all([loadAuditLogs(), loadCreditLedger(creditUser)]);
  }

  async function toggleProvider(provider: ProviderConfig) {
    const response = await fetch(`/api/ai-providers/${encodeURIComponent(provider.id)}`, {
      method: "PUT",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
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

  async function submitProvider(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const response = await fetch("/api/ai-providers", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        ...providerForm,
        priority: Number.parseInt(providerForm.priority, 10),
        enabled: true,
      }),
    });
    const data = await response.json();
    if (!response.ok) {
      setMessage(data.detail ? `模型新增失败：${data.detail}` : "模型新增失败。");
      return;
    }

    setProviderForm(emptyProvider);
    await loadProviders();
    await loadAuditLogs();
    setMessage(`已新增模型配置：${data.id}`);
  }

  async function refreshAdminData() {
    await Promise.all([loadProviders(), loadAuditLogs(), loadAiCallLogs(), loadCreditLedger()]);
  }

  return (
    <main className="workspace-page admin-page">
      <header className="workspace-header">
        <a href="/" className="brand-mark">
          <AppIcon icon="solar:soundwave-circle-bold-duotone" size={24} />
          面霸练习生
        </a>
        <span className="session-pill session-pill--admin">{currentUser ? currentUser.email : "Admin Console"}</span>
      </header>

      <section className="admin-hero">
        <span className="eyebrow">Operator Console</span>
        <h1>运营后台</h1>
        <p>后台用于手动发放面试次数、调整模型路由和检查系统状态。管理员必须在邮箱白名单内，并通过密码与邮箱验证码双重认证。</p>
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
              <p>启用中的模型供应商</p>
            </article>
            <article className="admin-card">
              <AppIcon icon="lucide:coins" size={24} />
              <h2>手动开通</h2>
              <p>先宣传获客，再由后台给用户发放面试次数。</p>
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
                    <span>{entry.reason} · 余额 {entry.balance_after}</span>
                  </div>
                ))}
              </div>
            </form>

            <form className="admin-panel" onSubmit={submitProvider}>
              <h2>新增模型配置</h2>
              <div className="admin-form-grid">
                <label>
                  配置 ID
                  <input value={providerForm.id} onChange={(event) => setProviderForm({ ...providerForm, id: event.target.value })} placeholder="zhipu-backup" required />
                </label>
                <label>
                  供应商
                  <input value={providerForm.provider_name} onChange={(event) => setProviderForm({ ...providerForm, provider_name: event.target.value })} placeholder="zhipu" required />
                </label>
                <label>
                  模型名
                  <input value={providerForm.model_name} onChange={(event) => setProviderForm({ ...providerForm, model_name: event.target.value })} placeholder="glm-4-flash" required />
                </label>
                <label>
                  优先级
                  <input type="number" value={providerForm.priority} onChange={(event) => setProviderForm({ ...providerForm, priority: event.target.value })} required />
                </label>
              </div>
              <button type="submit" className="admin-primary-button">新增模型</button>
            </form>
          </section>

          <section className="admin-provider-table">
            <div className="admin-section-heading">
              <span className="eyebrow">Model Router</span>
              <h2>模型路由配置</h2>
            </div>
            <div className="admin-provider-list">
              {providers.map((provider) => (
                <article className="provider-row" key={provider.id}>
                  <div>
                    <strong>{provider.id}</strong>
                    <span>{provider.provider_name} / {provider.model_name}</span>
                  </div>
                  <em>{provider.purpose} · priority {provider.priority}</em>
                  <button type="button" className={provider.enabled ? "is-enabled" : ""} onClick={() => toggleProvider(provider)}>
                    {provider.enabled ? "启用中" : "已停用"}
                  </button>
                </article>
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
