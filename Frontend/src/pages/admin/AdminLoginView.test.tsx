import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { forwardRef, useEffect, useImperativeHandle } from "react";
import { describe, expect, it, vi } from "vitest";
import type {
  HCaptchaChallengeHandle,
  HCaptchaChallengeProps,
} from "../../components/HCaptchaChallenge/HCaptchaChallenge";
import { AdminLoginView } from "./AdminLoginView";

const captchaControl = vi.hoisted(() => ({
  solve: null as null | ((token: string) => void),
  reset: vi.fn(),
}));

vi.mock("../../components/HCaptchaChallenge/HCaptchaChallenge", () => ({
  INITIAL_HCAPTCHA_STATE: { status: "loading", enabled: null, token: null },
  hCaptchaTokenForRequest: (state: { status: string; token: string | null }) =>
    state.status === "disabled" ? "" : state.status === "verified" ? state.token : null,
  HCaptchaChallenge: forwardRef<HCaptchaChallengeHandle, HCaptchaChallengeProps>(function FakeCaptcha(
    { onStateChange },
    ref,
  ) {
    useEffect(() => {
      onStateChange({ status: "ready", enabled: true, token: null });
      captchaControl.solve = (token) => onStateChange({ status: "verified", enabled: true, token });
      return () => {
        captchaControl.solve = null;
      };
    }, [onStateChange]);
    useImperativeHandle(ref, () => ({
      reset() {
        captchaControl.reset();
        onStateChange({ status: "ready", enabled: true, token: null });
      },
    }), [onStateChange]);
    return <button type="button" onClick={() => captchaControl.solve?.("fresh-token")}>完成人机验证</button>;
  }),
}));

describe("AdminLoginView", () => {
  it("验证码请求与管理员登录分别消费新 token 并在请求后重置", async () => {
    const onRequestCode = vi.fn().mockResolvedValue(undefined);
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <AdminLoginView
        isLoading={false}
        isRequestingCode={false}
        isSubmitting={false}
        codeCooldownSeconds={0}
        email="admin@example.com"
        password="password-123"
        code="123456"
        message="等待登录"
        onEmailChange={() => undefined}
        onPasswordChange={() => undefined}
        onCodeChange={() => undefined}
        onRequestCode={onRequestCode}
        onSubmit={onSubmit}
      />,
    );

    const requestCodeButton = screen.getByRole("button", { name: "获取" });
    expect(requestCodeButton).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "完成人机验证" }));
    expect(requestCodeButton).toBeEnabled();
    await user.click(requestCodeButton);
    await waitFor(() => expect(onRequestCode).toHaveBeenCalledWith("fresh-token"));
    expect(captchaControl.reset).toHaveBeenCalledTimes(1);

    const submitButton = screen.getByRole("button", { name: "进入后台" });
    expect(submitButton).toBeDisabled();
    captchaControl.solve?.("login-token");
    await waitFor(() => expect(submitButton).toBeEnabled());
    await user.click(submitButton);

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.anything(), "login-token"));
    expect(captchaControl.reset).toHaveBeenCalledTimes(2);
  });

  it("验证码请求未完成时禁止并发管理员登录", async () => {
    let finishRequest: (() => void) | undefined;
    const onRequestCode = vi.fn().mockReturnValue(new Promise<void>((resolve) => {
      finishRequest = resolve;
    }));
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <AdminLoginView
        isLoading={false}
        isRequestingCode={false}
        isSubmitting={false}
        codeCooldownSeconds={0}
        email="admin@example.com"
        password="password-123"
        code="123456"
        message="等待登录"
        onEmailChange={() => undefined}
        onPasswordChange={() => undefined}
        onCodeChange={() => undefined}
        onRequestCode={onRequestCode}
        onSubmit={onSubmit}
      />,
    );

    await user.click(screen.getByRole("button", { name: "完成人机验证" }));
    await user.click(screen.getByRole("button", { name: "获取" }));
    await waitFor(() => expect(onRequestCode).toHaveBeenCalledTimes(1));

    expect(screen.getByRole("button", { name: "进入后台" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "进入后台" }));
    expect(onSubmit).not.toHaveBeenCalled();

    finishRequest?.();
    await waitFor(() => expect(captchaControl.reset).toHaveBeenCalled());
  });
});
