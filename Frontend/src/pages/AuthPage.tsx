import { FormEvent, useState } from "react";
import { ArrowLeft, KeyRound, Mail, ShieldCheck, Sparkles } from "lucide-react";

type AuthMode = "login" | "register";

type AuthPageProps = {
  mode: AuthMode;
};

export function AuthPage({ mode }: AuthPageProps) {
  const [loginMethod, setLoginMethod] = useState<"code" | "password">("code");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [message, setMessage] = useState("邮箱验证码会在开发环境中返回，接入邮件服务后将发送到邮箱。");

  async function requestCode() {
    const response = await fetch("/api/auth/email-code/request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email })
    });
    const data = await response.json();
    setCode(data.dev_code ?? "");
    setMessage(data.dev_code ? `开发验证码：${data.dev_code}` : "验证码已发送");
  }

  async function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const endpoint =
      mode === "register"
        ? "/api/auth/password/register"
        : loginMethod === "password"
          ? "/api/auth/password/login"
          : "/api/auth/email-code/login";
    const payload =
      mode === "register" || loginMethod === "password"
        ? { email, password }
        : { email, code };

    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    setMessage(response.ok ? "认证成功，稍后将接入真实用户会话。" : "认证失败，请检查输入信息。");
  }

  return (
    <main className="auth-page">
      <a className="back-link" href="/">
        <ArrowLeft size={18} />
        返回首页
      </a>
      <section className="auth-shell">
        <div className="auth-narrative">
          <span className="eyebrow">Secure Practice</span>
          <h1>{mode === "login" ? "继续你的面试训练" : "创建练习生账户"}</h1>
          <p>用邮箱进入训练空间，保存每一次语音面试、追问记录和复盘报告。</p>
          <div className="auth-proof">
            <span><ShieldCheck size={18} /> 邮箱验证</span>
            <span><KeyRound size={18} /> 密码哈希</span>
            <span><Sparkles size={18} /> 报告留存</span>
          </div>
        </div>

        <form className="auth-card" onSubmit={submitForm}>
          <div className="auth-card-heading">
            <span>{mode === "login" ? "登录" : "注册"}</span>
            <a href={mode === "login" ? "/register" : "/login"}>
              {mode === "login" ? "创建账户" : "已有账户"}
            </a>
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
              <Mail size={18} />
              <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" required />
            </div>
          </label>

          {(mode === "register" || loginMethod === "password") && (
            <label>
              密码
              <div className="input-shell">
                <KeyRound size={18} />
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
                  <KeyRound size={18} />
                  <input value={code} onChange={(event) => setCode(event.target.value)} placeholder="6 位验证码" minLength={6} maxLength={6} required />
                </div>
                <button type="button" className="code-button" onClick={requestCode}>
                  获取
                </button>
              </div>
            </label>
          )}

          <button className="auth-submit" type="submit">
            {mode === "login" ? "进入账户" : "完成注册"}
          </button>
          <p className="auth-message">{message}</p>
        </form>
      </section>
    </main>
  );
}

