import type { FormEvent } from "react";
import { AppIcon } from "../../components/AppIcon";
import styles from "./AccountSettingsPanel.module.css";

type AccountSettingsPanelProps = {
  email: string;
  code: string;
  newPassword: string;
  message: string;
  isRequestingCode: boolean;
  isChangingPassword: boolean;
  codeCooldownSeconds: number;
  onCodeChange: (value: string) => void;
  onPasswordChange: (value: string) => void;
  onRequestCode: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
};

export function AccountSettingsPanel({
  email,
  code,
  newPassword,
  message,
  isRequestingCode,
  isChangingPassword,
  codeCooldownSeconds,
  onCodeChange,
  onPasswordChange,
  onRequestCode,
  onSubmit,
}: AccountSettingsPanelProps) {
  const codeButtonText = isRequestingCode
    ? "发送中"
    : codeCooldownSeconds > 0
    ? `${codeCooldownSeconds}s`
    : "获取验证码";

  return (
    <section className={styles.panel} aria-label="账户设置">
      <div className={styles.header}>
        <div className={styles.avatar} aria-hidden="true">
          <AppIcon icon="lucide:user-round" size={24} />
        </div>
        <div>
          <h2 className={styles.title}>账户设置</h2>
          <p className={styles.email}>{email}</p>
        </div>
      </div>

      <div className={styles.divider} />

      <form className={styles.form} onSubmit={onSubmit}>
        <p className={styles.hint}>使用注册邮箱验证码修改账户密码。</p>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="settings-code">邮箱验证码</label>
          <div className={styles.codeRow}>
            <div className={styles.inputShell}>
              <AppIcon icon="lucide:mail" size={16} />
              <input
                id="settings-code"
                className={styles.input}
                value={code}
                onChange={(e) => onCodeChange(e.currentTarget.value)}
                placeholder="6 位验证码"
                minLength={6}
                maxLength={6}
                required
              />
            </div>
            <button
              type="button"
              className={styles.codeButton}
              disabled={isRequestingCode || codeCooldownSeconds > 0}
              onClick={onRequestCode}
            >
              {codeButtonText}
            </button>
          </div>
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="settings-password">新密码</label>
          <div className={styles.inputShell}>
            <AppIcon icon="lucide:lock" size={16} />
            <input
              id="settings-password"
              className={styles.input}
              type="password"
              value={newPassword}
              onChange={(e) => onPasswordChange(e.currentTarget.value)}
              placeholder="至少 8 位"
              minLength={8}
              required
            />
          </div>
        </div>

        <button type="submit" className={styles.submitButton} disabled={isChangingPassword}>
          {isChangingPassword ? "修改中…" : "修改密码"}
        </button>

        {message && (
          <p className={styles.message} role="status" aria-live="polite">
            {message}
          </p>
        )}
      </form>
    </section>
  );
}
