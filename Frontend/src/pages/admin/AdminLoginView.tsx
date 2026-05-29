import type { FormEvent } from "react";
import { AppIcon } from "../../components/AppIcon";
import { BrandLogo } from "../../components/BrandLogo";

type AdminLoginViewProps = {
  isLoading: boolean;
  isRequestingCode: boolean;
  isSubmitting: boolean;
  codeCooldownSeconds: number;
  email: string;
  password: string;
  code: string;
  message: string;
  onEmailChange: (value: string) => void;
  onPasswordChange: (value: string) => void;
  onCodeChange: (value: string) => void;
  onRequestCode: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
};

export function AdminLoginView({
  isLoading,
  isRequestingCode,
  isSubmitting,
  codeCooldownSeconds,
  email,
  password,
  code,
  message,
  onEmailChange,
  onPasswordChange,
  onCodeChange,
  onRequestCode,
  onSubmit,
}: AdminLoginViewProps) {
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

        <form className="auth-card auth-glass-card admin-auth-card" onSubmit={onSubmit}>
          <div className="auth-card-heading admin-auth-card-heading">
            <span>{isLoading ? "正在检查会话" : "管理员登录"}</span>
          </div>
          <div className="auth-form-body">
            <label>
              管理员邮箱
              <div className="input-shell">
                <AppIcon icon="lucide:mail" size={18} />
                <input
                  type="email"
                  value={email}
                  onChange={(event) => onEmailChange(event.target.value)}
                  placeholder="admin@example.com"
                  autoComplete="username"
                  required
                />
              </div>
            </label>
            <label>
              密码
              <div className="input-shell">
                <AppIcon icon="lucide:key-round" size={18} />
                <input
                  type="password"
                  value={password}
                  onChange={(event) => onPasswordChange(event.target.value)}
                  placeholder="至少 8 位"
                  minLength={8}
                  autoComplete="current-password"
                  required
                />
              </div>
            </label>
            <label>
              邮箱验证码
              <div className="code-row">
                <div className="input-shell">
                  <AppIcon icon="lucide:key-round" size={18} />
                  <input
                    value={code}
                    onChange={(event) => onCodeChange(event.target.value)}
                    placeholder="6 位验证码"
                    minLength={6}
                    maxLength={6}
                    autoComplete="one-time-code"
                    required
                  />
                </div>
                <button
                  type="button"
                  className="code-button admin-auth-code-button"
                  onClick={onRequestCode}
                  disabled={isRequestingCode || codeCooldownSeconds > 0}
                >
                  {isRequestingCode ? "发送中" : codeCooldownSeconds > 0 ? `${codeCooldownSeconds}s` : "获取"}
                </button>
              </div>
            </label>
            <button type="submit" className="auth-submit admin-auth-submit" disabled={isLoading || isSubmitting}>
              {isLoading ? "检查中" : isSubmitting ? "登录中" : "进入后台"}
            </button>
            <p className="auth-message" role="status" aria-live="polite">
              {message}
            </p>
          </div>
        </form>
      </section>
    </main>
  );
}
