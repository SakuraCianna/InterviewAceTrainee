import { FormEvent, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Button, SafeArea } from "antd-mobile";
import { gsap } from "gsap";
import { AppIcon } from "../components/AppIcon";
import { getApiErrorMessage } from "../lib/api";
import { emailCodeCooldownKey, normalizeEmail, retryAfterSeconds, secondsUntil } from "../lib/emailCooldown";

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

function codeCooldownKey(email: string) {
  return emailCodeCooldownKey(CODE_REQUEST_STORAGE_PREFIX, email);
}

async function parseAuthResponse(response: Response): Promise<AuthResponse> {
  try {
    return (await response.json()) as AuthResponse;
  } catch {
    return {};
  }
}

function isReducedMotionEnabled() {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
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
  const pageRef = useRef<HTMLElement | null>(null);
  const backRef = useRef<HTMLAnchorElement | null>(null);
  const eyebrowRef = useRef<HTMLSpanElement | null>(null);
  const descRef = useRef<HTMLParagraphElement | null>(null);
  const statusCardRef = useRef<HTMLDivElement | null>(null);
  const authCardRef = useRef<HTMLFormElement | null>(null);
  const formBodyRef = useRef<HTMLDivElement | null>(null);
  const tabIndicatorRef = useRef<HTMLSpanElement | null>(null);
  const titleLineRefs = useRef<Array<HTMLSpanElement | null>>([]);
  const glowRefs = useRef<Array<HTMLSpanElement | null>>([]);

  useLayoutEffect(() => {
    if (!pageRef.current) {
      return;
    }
    const reducedMotion = isReducedMotionEnabled();
    const ctx = gsap.context(() => {
      const titleLines = titleLineRefs.current.filter(Boolean) as HTMLSpanElement[];
      const glows = glowRefs.current.filter(Boolean) as HTMLSpanElement[];
      const formItems = authCardRef.current
        ? Array.from(authCardRef.current.querySelectorAll<HTMLElement>(".auth-anim-field"))
        : [];
      const fadeItems = [backRef.current, eyebrowRef.current, descRef.current, statusCardRef.current, authCardRef.current, ...titleLines, ...formItems].filter(
        Boolean,
      ) as HTMLElement[];

      gsap.set(fadeItems, { opacity: 0 });
      gsap.set(backRef.current, reducedMotion ? {} : { y: 12 });
      gsap.set(titleLines, reducedMotion ? {} : { y: 28 });
      gsap.set(statusCardRef.current, reducedMotion ? {} : { y: 16 });
      gsap.set(formItems, reducedMotion ? {} : { y: 10 });
      gsap.set(authCardRef.current, reducedMotion ? {} : { x: 34, scale: 0.98, filter: "blur(10px)" });

      if (!reducedMotion) {
        glows.forEach((glow, index) => {
          gsap.to(glow, {
            x: [24, -18, 14][index] ?? 18,
            y: [-18, 22, -12][index] ?? 16,
            scale: [1.08, 1.12, 1.04][index] ?? 1.06,
            opacity: [0.72, 0.62, 0.58][index] ?? 0.62,
            duration: [8.5, 10.5, 12][index] ?? 9,
            ease: "sine.inOut",
            repeat: -1,
            yoyo: true,
          });
        });
      }

      const tl = gsap.timeline({ defaults: { ease: "power3.out" } });
      if (reducedMotion) {
        tl.to(fadeItems, { opacity: 1, duration: 0.24, stagger: 0.025 });
        return;
      }
      tl.to(backRef.current, { opacity: 1, y: 0, duration: 0.45 })
        .to(eyebrowRef.current, { opacity: 1, y: 0, duration: 0.36 }, "-=0.2")
        .to(titleLines, { opacity: 1, y: 0, duration: 0.56, stagger: 0.08 }, "-=0.08")
        .to(descRef.current, { opacity: 1, y: 0, duration: 0.42 }, "-=0.22")
        .to(statusCardRef.current, { opacity: 1, y: 0, duration: 0.42 }, "-=0.18")
        .to(authCardRef.current, { opacity: 1, x: 0, scale: 1, filter: "blur(0px)", duration: 0.64 }, "-=0.4")
        .to(formItems, { opacity: 1, y: 0, duration: 0.34, stagger: 0.055 }, "-=0.32");
    }, pageRef);

    return () => ctx.revert();
  }, [mode]);

  useLayoutEffect(() => {
    const reducedMotion = isReducedMotionEnabled();
    if (tabIndicatorRef.current) {
      gsap.killTweensOf(tabIndicatorRef.current);
      gsap.to(tabIndicatorRef.current, {
        xPercent: loginMethod === "password" ? 0 : 100,
        duration: reducedMotion ? 0 : 0.32,
        ease: "power3.out",
      });
    }
    if (formBodyRef.current) {
      gsap.killTweensOf(formBodyRef.current);
      gsap.fromTo(
        formBodyRef.current,
        { opacity: 0, y: reducedMotion ? 0 : 8 },
        { opacity: 1, y: 0, duration: reducedMotion ? 0.16 : 0.24, ease: "power2.out" },
      );
    }
  }, [loginMethod, isResetMode, mode]);

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
        const retryAfter = retryAfterSeconds(response, CODE_REQUEST_COOLDOWN_SECONDS);
        startCodeCooldown(retryAfter);
        setMessage(`获取太频繁, 请 ${retryAfter} 秒后再试`);
        return;
      }
      const errorMessage = getApiErrorMessage(data, "请稍后再试");
      setMessage(`验证码发送失败: ${errorMessage}`);
      return;
    }

    setCode(data.dev_code ?? "");
    startCodeCooldown(CODE_REQUEST_COOLDOWN_SECONDS);
    setMessage(data.dev_code ? `开发验证码: ${data.dev_code}` : "验证码已发送, 5 分钟内有效, 请查看邮箱");
  }

  async function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mode === "login" && isResetMode) {
      await submitPasswordReset();
      return;
    }
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
      const errorMessage = getApiErrorMessage(data, "请检查输入信息");
      setMessage(`认证失败: ${errorMessage}`);
      return;
    }

    setMessage("认证成功, 正在进入训练空间");
    window.setTimeout(() => {
      window.location.href = "/interview";
    }, 350);
  }

  async function submitPasswordReset() {
    if (!code.trim() || password.trim().length < 8) {
      setMessage("请填写 6 位验证码和至少 8 位新密码");
      return;
    }
    setIsSubmitting(true);
    setMessage("正在重置密码...");
    let response: Response;
    try {
      response = await fetch("/api/auth/password/reset", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: normalizeEmail(email), code, new_password: password }),
      });
    } catch {
      setIsSubmitting(false);
      setMessage("网络连接异常, 请稍后再试");
      return;
    }
    const data = await parseAuthResponse(response);
    setIsSubmitting(false);
    if (!response.ok) {
      const errorMessage = getApiErrorMessage(data, "请检查验证码或账户状态");
      setMessage(`密码重置失败: ${errorMessage}`);
      return;
    }
    setIsResetMode(false);
    setLoginMethod("password");
    setCode("");
    setMessage("密码已重置, 请使用新密码登录");
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
  const authTitleLines = mode === "login" ? ["欢迎来到面霸", "练习生"] : ["创建练习生", "账户"];
  const statusItems = [
    { icon: "lucide:mic-2", label: "语音模拟面试" },
    { icon: "lucide:file-scan", label: "简历/JD 分析" },
    { icon: "lucide:receipt-text", label: "面试报告生成" },
  ];

  return (
    <main className="auth-page" ref={pageRef}>
      <div className="auth-ambient" aria-hidden="true">
        <span ref={(node) => { glowRefs.current[0] = node; }} className="auth-glow auth-glow-blue" />
        <span ref={(node) => { glowRefs.current[1] = node; }} className="auth-glow auth-glow-lime" />
        <span ref={(node) => { glowRefs.current[2] = node; }} className="auth-glow auth-glow-fog" />
      </div>
      <a className="back-link auth-anim-back" href="/" ref={backRef}>
        <AppIcon icon="lucide:arrow-left" size={18} />
        返回首页
      </a>
      <section className="auth-shell">
        <div className="auth-narrative">
          <span className="eyebrow" ref={eyebrowRef}>Secure Practice</span>
          <h1 className="auth-title-login">
            {authTitleLines.map((line, index) => (
              <span
                className="auth-title-line"
                key={line}
                ref={(node) => {
                  titleLineRefs.current[index] = node;
                }}
              >
                {line}
              </span>
            ))}
          </h1>
          <p ref={descRef}>使用邮箱进入训练空间, 完成语音模拟, 查看复盘报告, 也可以回到未完成的训练继续推进</p>
          <div className="auth-status-card" ref={statusCardRef} aria-label="训练能力状态">
            {statusItems.map((item) => (
              <span key={item.label}>
                <AppIcon icon={item.icon} size={18} />
                {item.label}
              </span>
            ))}
          </div>
        </div>

        <form className="auth-card auth-glass-card" onSubmit={submitForm} ref={authCardRef}>
          <div className="auth-card-heading auth-anim-field">
            <span>{mode === "login" ? (isResetMode ? "重置密码" : "登录") : "注册"}</span>
            {mode === "login" && isResetMode ? (
              <button type="button" className="auth-heading-link" onClick={closeResetMode}>返回登录</button>
            ) : (
              <a href={mode === "login" ? "/register" : "/login"}>{mode === "login" ? "创建账户" : "已有账户"}</a>
            )}
          </div>

          {mode === "login" && !isResetMode && (
            <div className="auth-segmented-control auth-anim-field" role="tablist" aria-label="登录方式">
              <span className="auth-tab-indicator" ref={tabIndicatorRef} aria-hidden="true" />
              <button
                type="button"
                role="tab"
                aria-selected={loginMethod === "password"}
                className={loginMethod === "password" ? "is-active" : ""}
                onClick={() => setLoginMethod("password")}
              >
                密码登录
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={loginMethod === "code"}
                className={loginMethod === "code" ? "is-active" : ""}
                onClick={() => setLoginMethod("code")}
              >
                验证码登录
              </button>
            </div>
          )}

          <div className="auth-form-body" ref={formBodyRef}>
            <label className="auth-anim-field">
              邮箱
              <div className="input-shell">
                <AppIcon icon="lucide:mail" size={18} />
                <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" required />
              </div>
            </label>

            {(mode === "register" || isResetMode) && (
              <label className="auth-anim-field">
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
              <label className="auth-anim-field">
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
              <label className="auth-anim-field">
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
              <div className="auth-inline-actions auth-anim-field">
                <button type="button" onClick={openResetMode}>忘记密码?</button>
              </div>
            )}

            <Button className="auth-submit auth-anim-field" color="primary" loading={isSubmitting} shape="rounded" disabled={isSubmitting} type="submit" block>
              {isSubmitting ? "处理中" : mode === "login" ? (isResetMode ? "重置密码" : "进入账户") : "完成注册"}
            </Button>
            <p className="auth-message auth-anim-field" role="status" aria-live="polite">{message}</p>
          </div>
        </form>
      </section>
      <SafeArea position="bottom" />
    </main>
  );
}
