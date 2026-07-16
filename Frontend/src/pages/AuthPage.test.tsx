import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { forwardRef, useEffect, useImperativeHandle } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  HCaptchaChallengeHandle,
  HCaptchaChallengeProps,
  HCaptchaChallengeState,
} from "../components/HCaptchaChallenge/HCaptchaChallenge";
import { AuthPage } from "./AuthPage";

const captchaMock = vi.hoisted(() => ({
  reset: vi.fn(),
  state: { status: "verified", enabled: true, token: "password-token" } as HCaptchaChallengeState,
}));

vi.mock("../components/HCaptchaChallenge/HCaptchaChallenge", () => ({
  INITIAL_HCAPTCHA_STATE: { status: "loading", enabled: null, token: null },
  hCaptchaTokenForRequest: (state: { status: string; token: string | null }) =>
    state.status === "disabled" ? "" : state.status === "verified" ? state.token : null,
  HCaptchaChallenge: forwardRef<HCaptchaChallengeHandle, HCaptchaChallengeProps>(function FakeCaptcha(
    { onStateChange },
    ref,
  ) {
    useEffect(() => {
      onStateChange(captchaMock.state);
    }, [onStateChange]);
    useImperativeHandle(ref, () => ({
      reset() {
        captchaMock.reset();
        onStateChange({ status: "ready", enabled: true, token: null });
      },
    }), [onStateChange]);
    return <div>人机验证已完成</div>;
  }),
}));

vi.mock("gsap", () => {
  const timeline = { to: vi.fn() };
  timeline.to.mockReturnValue(timeline);
  return {
    gsap: {
      context: (callback: () => void) => {
        callback();
        return { revert: vi.fn() };
      },
      set: vi.fn(),
      to: vi.fn(),
      fromTo: vi.fn(),
      killTweensOf: vi.fn(),
      timeline: () => timeline,
    },
  };
});

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("AuthPage hCaptcha", () => {
  beforeEach(() => {
    window.localStorage.clear();
    captchaMock.reset.mockClear();
    captchaMock.state = { status: "verified", enabled: true, token: "password-token" };
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: vi.fn().mockReturnValue({ matches: true }),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("密码登录携带 token 并在响应后重置挑战", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ detail: "invalid_credentials" }, 401));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<AuthPage mode="login" />);

    await user.type(screen.getByLabelText("邮箱"), "user@example.com");
    await user.type(screen.getByLabelText("密码"), "password-123");
    await user.click(screen.getByRole("button", { name: "进入账户" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(request.body))).toEqual({
      email: "user@example.com",
      password: "password-123",
      captcha_token: "password-token",
    });
    expect(captchaMock.reset).toHaveBeenCalledTimes(1);
  });

  it("邮箱验证码请求携带 token 并在响应后重置挑战", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ dev_code: "123456" }));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<AuthPage mode="register" />);

    await user.type(screen.getByLabelText("邮箱"), "user@example.com");
    await user.click(screen.getByRole("button", { name: "获取" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(request.body))).toEqual({
      email: "user@example.com",
      captcha_token: "password-token",
    });
    expect(captchaMock.reset).toHaveBeenCalledTimes(1);
  });

  it("注册最终提交在没有 token 时仍可执行且不携带 captcha 字段", async () => {
    captchaMock.state = { status: "ready", enabled: true, token: null };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ detail: "registration_closed" }, 403));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<AuthPage mode="register" />);

    await user.type(screen.getByLabelText("邮箱"), "user@example.com");
    await user.type(screen.getByLabelText("验证码"), "123456");
    await user.type(screen.getByLabelText("密码"), "password-123");
    await user.click(screen.getByRole("button", { name: "完成注册" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(request.body))).toEqual({
      email: "user@example.com",
      password: "password-123",
      code: "123456",
    });
  });

  it("服务端关闭 hCaptcha 时密码登录不要求 token", async () => {
    captchaMock.state = { status: "disabled", enabled: false, token: null };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ detail: "invalid_credentials" }, 401));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<AuthPage mode="login" />);

    await user.type(screen.getByLabelText("邮箱"), "user@example.com");
    await user.type(screen.getByLabelText("密码"), "password-123");
    const submit = screen.getByRole("button", { name: "进入账户" });
    expect(submit).toBeEnabled();
    await user.click(submit);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(request.body))).toMatchObject({ captcha_token: "" });
  });

  it("验证码请求未完成时禁止切换到密码登录复用同一 token", async () => {
    let resolveRequest: ((response: Response) => void) | undefined;
    const fetchMock = vi.fn().mockReturnValue(new Promise<Response>((resolve) => {
      resolveRequest = resolve;
    }));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<AuthPage mode="login" />);

    await user.type(screen.getByLabelText("邮箱"), "user@example.com");
    await user.click(screen.getByRole("tab", { name: "验证码登录" }));
    await user.click(screen.getByRole("button", { name: "获取" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole("tab", { name: "密码登录" }));
    await user.type(screen.getByLabelText("密码"), "password-123");
    expect(screen.getByRole("button", { name: "进入账户" })).toBeDisabled();

    await act(async () => {
      resolveRequest?.(jsonResponse({ dev_code: "123456" }));
    });
    await waitFor(() => expect(captchaMock.reset).toHaveBeenCalledTimes(1));
  });
});
