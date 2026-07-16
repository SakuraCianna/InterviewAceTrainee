import type { FormEvent } from "react";
import { Button } from "antd-mobile";
import { AppIcon } from "../../components/AppIcon";
import { roomClasses } from "./roomStyles";

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
  return (
    <section className={roomClasses("account-settings-panel")} aria-label="账户设置">
      <div className={roomClasses("account-settings-copy")}>
        <span className={roomClasses("eyebrow")}>Account Settings</span>
        <h2>账户与密码</h2>
        <p>{email}</p>
      </div>
      <form className={roomClasses("account-password-form")} onSubmit={onSubmit}>
        <label>
          <span>邮箱验证码</span>
          <div className={roomClasses("code-row")}>
            <div className={roomClasses("input-shell")}>
              <AppIcon icon="lucide:key-round" size={17} />
              <input
                value={code}
                onChange={(event) => onCodeChange(event.currentTarget.value)}
                placeholder="6 位验证码"
                minLength={6}
                maxLength={6}
                required
              />
            </div>
            <Button
              type="button"
              className={roomClasses("code-button")}
              fill="outline"
              shape="rounded"
              loading={isRequestingCode}
              disabled={isRequestingCode || codeCooldownSeconds > 0}
              onClick={onRequestCode}
            >
              {isRequestingCode ? "发送中" : codeCooldownSeconds > 0 ? `${codeCooldownSeconds}s` : "获取"}
            </Button>
          </div>
        </label>
        <label>
          <span>新密码</span>
          <div className={roomClasses("input-shell")}>
            <AppIcon icon="lucide:key-round" size={17} />
            <input
              type="password"
              value={newPassword}
              onChange={(event) => onPasswordChange(event.currentTarget.value)}
              placeholder="至少 8 位"
              minLength={8}
              required
            />
          </div>
        </label>
        <Button
          className={roomClasses("auth-submit account-password-submit")}
          color="primary"
          loading={isChangingPassword}
          shape="rounded"
          type="submit"
        >
          {isChangingPassword ? "修改中" : "修改密码"}
        </Button>
        <p className={roomClasses("account-settings-message")} role="status" aria-live="polite">
          {message}
        </p>
      </form>
    </section>
  );
}
