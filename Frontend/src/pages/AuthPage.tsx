import { FormEvent, useEffect, useState } from "react";
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

export function AuthPage({ mode }: AuthPageProps) {
  const [loginMethod, setLoginMethod] = useState<"code" | "password">("code");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [message, setMessage] = useState("先填写邮箱获取验证码, 再完成注册或登录");
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
      return;
    }
    if (codeCooldownSeconds > 0) {
      setMessage(`验证码已发送, ${codeCooldownSeconds} 秒后可以重新获取`);
      return;
    }

    setIsRequestingCode(true);
    setMessage("正在发送验证码...");
    let response: Response;
    try {
      response = await fetch("/api/auth/email-code/request", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: normalizedEmail }),
      });
    } catch {
      setIsRequestingCode(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const data = await parseAuthResponse(response);
    setIsRequestingCode(false);

    if (!response.ok) {
      if (response.status === 429) {
        const retryAfter = retryAfterSeconds(response);
        startCodeCooldown(retryAfter);
        setMessage(`获取太频繁, 请 ${retryAfter} 秒后再试`);
        return;
      }
      setMessage(`验证码发送失败: ${getApiErrorMessage(data, "请稍后再试")}`);
      return;
    }

    setCode(data.dev_code ?? "");
    startCodeCooldown(CODE_REQUEST_COOLDOWN_SECONDS);
    setMessage(data.dev_code ? `开发验证码: ${data.dev_code}` : "验证码已发送, 5 分钟内有效, 请查看邮箱");
  }

  async function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setIsSubmitting(true);
    setMessage("正在验证账户信息...");

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
      setIsSubmitting(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const data = await parseAuthResponse(response);
    setIsSubmitting(false);

    if (!response.ok || !data.access_token) {
      setMessage(`认证失败: ${getApiErrorMessage(data, "请检查输入信息")}`);
      return;
    }

    setMessage("认证成功, 正在进入训练空间");
    window.setTimeout(() => {
      window.location.href = "/interview";
    }, 350);
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
          <h1>{mode === "login" ? "欢迎来到面霸练习生" : "创建练习生账户"}</h1>
          <p>使用邮箱进入训练空间, 完成语音模拟, 查看复盘报告, 也可以回到未完成的训练继续推进</p>
        </div>

        <form className="auth-card" onSubmit={submitForm}>
          <div className="auth-card-heading">
            <span>{mode === "login" ? "登录" : "注册"}</span>
            <a href={mode === "login" ? "/register" : "/login"}>{mode === "login" ? "创建账户" : "已有账户"}</a>
          </div>

          {mode === "login" && (
            <div className="auth-tabs" role="tablist" aria-label="登录方式">
              <button type="button" className={loginMethod === "code" ? "active" : ""} onClick={() => setLoginMethod("code")}>
                验证码
              </button>
              <button type="button" className={loginMethod === "password" ? "active" : ""} onClick={() => setLoginMethod("password")}>
                密码
              </button>
            </div>
          )}

          <label>
            邮箱
            <div className="input-shell">
              <AppIcon icon="lucide:mail" size={18} />
              <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" required />
            </div>
          </label>

          {mode === "register" && (
            <label>
              验证码
              <div className="code-row">
                <div className="input-shell">
                  <AppIcon icon="lucide:key-round" size={18} />
                  <input value={code} onChange={(event) => setCode(event.target.value)} placeholder="6 位验证码" minLength={6} maxLength={6} required />
                </div>
                <button type="button" className="code-button" onClick={requestCode} disabled={codeButtonDisabled}>
                  {codeButtonText}
                </button>
              </div>
            </label>
          )}

          {(mode === "register" || loginMethod === "password") && (
            <label>
              密码
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

          {mode === "login" && loginMethod === "code" && (
            <label>
              验证码
              <div className="code-row">
                <div className="input-shell">
                  <AppIcon icon="lucide:key-round" size={18} />
                  <input value={code} onChange={(event) => setCode(event.target.value)} placeholder="6 位验证码" minLength={6} maxLength={6} required />
                </div>
                <button type="button" className="code-button" onClick={requestCode} disabled={codeButtonDisabled}>
                  {codeButtonText}
                </button>
              </div>
            </label>
          )}

          <button className="auth-submit" disabled={isSubmitting} type="submit">
            {isSubmitting ? "处理中" : mode === "login" ? "进入账户" : "完成注册"}
          </button>
          <p className="auth-message" role="status" aria-live="polite">{message}</p>
        </form>
      </section>
    </main>
  );
}
