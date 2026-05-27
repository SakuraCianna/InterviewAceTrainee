import { FormEvent, useEffect, useState } from "react";
import { Button, SafeArea, Tabs, Toast } from "antd-mobile";
import { AppIcon } from "../components/AppIcon";
import { getApiErrorMessage } from "../lib/api";

type AuthMode = "login" | "register";

type AuthPageProps = {
  mode: AuthMode;
};

type AuthResponse = {
  access_token?: string;
  token_type?: string;
  dev_code?: string;
  detail?: string;
  message?: string;
};

const CODE_REQUEST_COOLDOWN_SECONDS = 90;
const CODE_REQUEST_STORAGE_PREFIX = "mianba_email_code_next:";

function normalizeEmail(email: string) {
  return email.trim().toLowerCase();
}

function codeCooldownKey(email: string) {
  return `${CODE_REQUEST_STORAGE_PREFIX}${normalizeEmail(email)}`;
}

function secondsUntil(timestamp: number) {
  return Math.max(0, Math.ceil((timestamp - Date.now()) / 1000));
}

async function parseAuthResponse(response: Response): Promise<AuthResponse> {
  try {
    return (await response.json()) as AuthResponse;
  } catch {
    return {};
  }
}

function retryAfterSeconds(response: Response) {
  const rawValue = response.headers.get("Retry-After");
  const parsedValue = rawValue ? Number.parseInt(rawValue, 10) : CODE_REQUEST_COOLDOWN_SECONDS;
  return Number.isFinite(parsedValue) && parsedValue > 0 ? parsedValue : CODE_REQUEST_COOLDOWN_SECONDS;
}

function showToast(content: string, icon?: "success" | "fail" | "loading", duration = 2200) {
  Toast.show({ content, icon, duration, position: "top" });
}

function showLoadingToast(content: string) {
  Toast.show({ content, icon: "loading", duration: 0, position: "top" });
}

export function AuthPage({ mode }: AuthPageProps) {
  const [loginMethod, setLoginMethod] = useState<"code" | "password">("password");
  const [isResetMode, setIsResetMode] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [message, setMessage] = useState(mode === "login" ? "默认使用密码登录, 也可以切换邮箱验证码" : "先填写邮箱获取验证码, 再完成注册");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isRequestingCode, setIsRequestingCode] = useState(false);
  const [codeCooldownSeconds, setCodeCooldownSeconds] = useState(0);

  useEffect(() => {
    const normalizedEmail = normalizeEmail(email);
    if (!normalizedEmail) {
      setCodeCooldownSeconds(0);
      return;
    }
    const nextAllowedAt = Number.parseInt(window.localStorage.getItem(codeCooldownKey(normalizedEmail)) ?? "0", 10);
    setCodeCooldownSeconds(secondsUntil(nextAllowedAt));
  }, [email]);

  useEffect(() => {
    if (codeCooldownSeconds <= 0) {
      return;
    }
    const timer = window.setInterval(() => {
      const nextAllowedAt = Number.parseInt(window.localStorage.getItem(codeCooldownKey(email)) ?? "0", 10);
      setCodeCooldownSeconds(secondsUntil(nextAllowedAt));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [codeCooldownSeconds, email]);

  function startCodeCooldown(seconds = CODE_REQUEST_COOLDOWN_SECONDS) {
    const normalizedEmail = normalizeEmail(email);
    if (!normalizedEmail) {
      return;
    }
    const safeSeconds = Math.max(1, seconds);
    window.localStorage.setItem(codeCooldownKey(normalizedEmail), String(Date.now() + safeSeconds * 1000));
    setCodeCooldownSeconds(safeSeconds);
  }

  async function requestCode() {
    const normalizedEmail = normalizeEmail(email);
    if (!normalizedEmail) {
      setMessage("请先填写邮箱");
      showToast("请先填写邮箱", "fail");
      return;
    }
    if (codeCooldownSeconds > 0) {
      setMessage(`验证码已发送, ${codeCooldownSeconds} 秒后可以重新获取`);
      showToast(`${codeCooldownSeconds} 秒后可以重新获取验证码`);
      return;
    }

    setIsRequestingCode(true);
    setMessage("正在发送验证码...");
    showLoadingToast("正在发送验证码...");
    let response: Response;
    try {
      response = await fetch("/api/auth/email-code/request", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: normalizedEmail }),
      });
    } catch {
      Toast.clear();
      setIsRequestingCode(false);
      setMessage("网络连接异常, 请稍后再试");
      showToast("网络连接异常, 请稍后再试", "fail");
      return;
    }
    const data = await parseAuthResponse(response);
    setIsRequestingCode(false);
    Toast.clear();

    if (!response.ok) {
      if (response.status === 429) {
        const retryAfter = retryAfterSeconds(response);
        startCodeCooldown(retryAfter);
        setMessage(`获取太频繁, 请 ${retryAfter} 秒后再试`);
        showToast(`获取太频繁, 请 ${retryAfter} 秒后再试`, "fail");
        return;
      }
      const errorMessage = getApiErrorMessage(data, "请稍后再试");
      setMessage(`验证码发送失败: ${errorMessage}`);
      showToast(`验证码发送失败: ${errorMessage}`, "fail");
      return;
    }

    setCode(data.dev_code ?? "");
    startCodeCooldown(CODE_REQUEST_COOLDOWN_SECONDS);
    setMessage(data.dev_code ? `开发验证码: ${data.dev_code}` : "验证码已发送, 5 分钟内有效, 请查看邮箱");
    showToast("验证码已发送, 5 分钟内有效", "success");
  }

  async function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mode === "login" && isResetMode) {
      await submitPasswordReset();
      return;
    }
    setIsSubmitting(true);
    setMessage("正在验证账户信息...");
    showLoadingToast("正在验证账户信息...");

    const endpoint =
      mode === "register"
        ? "/api/auth/password/register"
        : loginMethod === "password"
          ? "/api/auth/password/login"
          : "/api/auth/email-code/login";
    const payload =
      mode === "register"
        ? { email: normalizeEmail(email), password, code }
        : loginMethod === "password"
          ? { email: normalizeEmail(email), password }
          : { email: normalizeEmail(email), code };

    let response: Response;
    try {
      response = await fetch(endpoint, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
    } catch {
      Toast.clear();
      setIsSubmitting(false);
      setMessage("网络连接异常, 请稍后再试");
      showToast("网络连接异常, 请稍后再试", "fail");
      return;
    }
    const data = await parseAuthResponse(response);
    setIsSubmitting(false);
    Toast.clear();

    if (!response.ok || !data.access_token) {
      const errorMessage = getApiErrorMessage(data, "请检查输入信息");
      setMessage(`认证失败: ${errorMessage}`);
      showToast(`认证失败: ${errorMessage}`, "fail");
      return;
    }

    setMessage("认证成功, 正在进入训练空间");
    showToast("认证成功, 正在进入训练空间", "success", 1200);
    window.setTimeout(() => {
      window.location.href = "/interview";
    }, 350);
  }

  async function submitPasswordReset() {
    if (!code.trim() || password.trim().length < 8) {
      setMessage("请填写 6 位验证码和至少 8 位新密码");
      showToast("请填写验证码和新密码", "fail");
      return;
    }
    setIsSubmitting(true);
    setMessage("正在重置密码...");
    showLoadingToast("正在重置密码...");
    let response: Response;
    try {
      response = await fetch("/api/auth/password/reset", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: normalizeEmail(email), code, new_password: password }),
      });
    } catch {
      Toast.clear();
      setIsSubmitting(false);
      setMessage("网络连接异常, 请稍后再试");
      showToast("网络连接异常, 请稍后再试", "fail");
      return;
    }
    const data = await parseAuthResponse(response);
    setIsSubmitting(false);
    Toast.clear();
    if (!response.ok) {
      const errorMessage = getApiErrorMessage(data, "请检查验证码或账户状态");
      setMessage(`密码重置失败: ${errorMessage}`);
      showToast(`密码重置失败: ${errorMessage}`, "fail");
      return;
    }
    setIsResetMode(false);
    setLoginMethod("password");
    setCode("");
    setMessage("密码已重置, 请使用新密码登录");
    showToast("密码已重置, 请重新登录", "success");
  }

  function openResetMode() {
    setIsResetMode(true);
    setLoginMethod("password");
    setCode("");
    setPassword("");
    setMessage("填写邮箱后获取验证码, 再设置新密码");
  }

  function closeResetMode() {
    setIsResetMode(false);
    setCode("");
    setPassword("");
    setMessage("默认使用密码登录, 也可以切换邮箱验证码");
  }

  const codeButtonText = isRequestingCode ? "发送中" : codeCooldownSeconds > 0 ? `${codeCooldownSeconds}s` : "获取";
  const codeButtonDisabled = isRequestingCode || codeCooldownSeconds > 0;

  return (
    <main className="auth-page">
      <a className="back-link" href="/">
        <AppIcon icon="lucide:arrow-left" size={18} />
        返回首页
      </a>
      <section className="auth-shell">
        <div className="auth-narrative">
          <span className="eyebrow">Secure Practice</span>
          <h1 className={mode === "login" ? "auth-title-login" : undefined}>
            {mode === "login" ? (
              <>
                <span>欢迎来到面霸</span>
                <span>练习生</span>
              </>
            ) : (
              "创建练习生账户"
            )}
          </h1>
          <p>使用邮箱进入训练空间, 完成语音模拟, 查看复盘报告, 也可以回到未完成的训练继续推进</p>
        </div>

        <form className="auth-card" onSubmit={submitForm}>
          <div className="auth-card-heading">
            <span>{mode === "login" ? (isResetMode ? "重置密码" : "登录") : "注册"}</span>
            {mode === "login" && isResetMode ? (
              <button type="button" className="auth-heading-link" onClick={closeResetMode}>返回登录</button>
            ) : (
              <a href={mode === "login" ? "/register" : "/login"}>{mode === "login" ? "创建账户" : "已有账户"}</a>
            )}
          </div>

          {mode === "login" && !isResetMode && (
            <Tabs
              activeKey={loginMethod}
              activeLineMode="fixed"
              className="auth-tabs auth-tabs-mobile"
              onChange={(key) => setLoginMethod(key === "password" ? "password" : "code")}
              stretch
            >
              <Tabs.Tab title="密码" key="password" />
              <Tabs.Tab title="验证码" key="code" />
            </Tabs>
          )}

          <label>
            邮箱
            <div className="input-shell">
              <AppIcon icon="lucide:mail" size={18} />
              <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" required />
            </div>
          </label>

          {(mode === "register" || isResetMode) && (
            <label>
              验证码
              <div className="code-row">
                <div className="input-shell">
                  <AppIcon icon="lucide:key-round" size={18} />
                  <input value={code} onChange={(event) => setCode(event.target.value)} placeholder="6 位验证码" minLength={6} maxLength={6} required />
                </div>
                <Button
                  type="button"
                  className="code-button"
                  fill="outline"
                  shape="rounded"
                  loading={isRequestingCode}
                  disabled={codeButtonDisabled}
                  onClick={requestCode}
                >
                  {codeButtonText}
                </Button>
              </div>
            </label>
          )}

          {(mode === "register" || loginMethod === "password" || isResetMode) && (
            <label>
              {isResetMode ? "新密码" : "密码"}
              <div className="input-shell">
                <AppIcon icon="lucide:key-round" size={18} />
                <input
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  placeholder="至少 8 位"
                  minLength={8}
                  required
                />
              </div>
            </label>
          )}

          {mode === "login" && !isResetMode && loginMethod === "code" && (
            <label>
              验证码
              <div className="code-row">
                <div className="input-shell">
                  <AppIcon icon="lucide:key-round" size={18} />
                  <input value={code} onChange={(event) => setCode(event.target.value)} placeholder="6 位验证码" minLength={6} maxLength={6} required />
                </div>
                <Button
                  type="button"
                  className="code-button"
                  fill="outline"
                  shape="rounded"
                  loading={isRequestingCode}
                  disabled={codeButtonDisabled}
                  onClick={requestCode}
                >
                  {codeButtonText}
                </Button>
              </div>
            </label>
          )}

          {mode === "login" && !isResetMode && loginMethod === "password" && (
            <div className="auth-inline-actions">
              <button type="button" onClick={openResetMode}>忘记密码?</button>
            </div>
          )}

          <Button className="auth-submit" color="primary" loading={isSubmitting} shape="rounded" disabled={isSubmitting} type="submit" block>
            {isSubmitting ? "处理中" : mode === "login" ? (isResetMode ? "重置密码" : "进入账户") : "完成注册"}
          </Button>
          <p className="auth-message" role="status" aria-live="polite">{message}</p>
        </form>
      </section>
      <SafeArea position="bottom" />
    </main>
  );
}
