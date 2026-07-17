import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  HCaptchaChallenge,
  type HCaptchaChallengeHandle,
  type HCaptchaChallengeState,
} from "./HCaptchaChallenge";
import {
  HCAPTCHA_ONLOAD_CALLBACK_NAME,
  HCAPTCHA_SCRIPT_SRC,
  loadHCaptchaSdk,
  type HCaptchaApi,
  type HCaptchaRenderParameters,
} from "./hcaptchaSdk";

type HCaptchaWindow = Window & {
  hcaptcha?: HCaptchaApi;
  [HCAPTCHA_ONLOAD_CALLBACK_NAME]?: () => void;
};

function hCaptchaScripts() {
  return Array.from(document.querySelectorAll("script")).filter(
    (script) => script.getAttribute("src")?.startsWith(HCAPTCHA_SCRIPT_SRC),
  );
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function installSdk() {
  let parameters: HCaptchaRenderParameters | undefined;
  const api: HCaptchaApi = {
    render: vi.fn((_container, nextParameters) => {
      parameters = nextParameters;
      return "widget-1";
    }),
    reset: vi.fn(),
    remove: vi.fn(),
  };
  (window as HCaptchaWindow).hcaptcha = api;
  return { api, getParameters: () => parameters };
}

describe("HCaptchaChallenge", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: vi.fn().mockReturnValue({ matches: false }),
    });
  });

  afterEach(() => {
    delete (window as HCaptchaWindow).hcaptcha;
    hCaptchaScripts().forEach((script) => script.remove());
    vi.unstubAllGlobals();
  });

  it("服务端关闭验证时不加载脚本也不渲染占位", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ enabled: false, site_key: "" }));
    const states: HCaptchaChallengeState[] = [];

    const { container } = render(<HCaptchaChallenge onStateChange={(state) => states.push(state)} />);

    await waitFor(() => expect(states.at(-1)?.status).toBe("disabled"));
    expect(container).toBeEmptyDOMElement();
    expect(hCaptchaScripts()).toHaveLength(0);
  });

  it("处理成功、过期、错误、手动重置与卸载清理", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ enabled: true, site_key: "site-key" }));
    const { api, getParameters } = installSdk();
    const states: HCaptchaChallengeState[] = [];
    const ref = createRef<HCaptchaChallengeHandle>();

    const { unmount } = render(
      <HCaptchaChallenge ref={ref} onStateChange={(state) => states.push(state)} />,
    );
    await waitFor(() => expect(api.render).toHaveBeenCalledTimes(1));

    act(() => getParameters()?.callback("one-time-token"));
    expect(states.at(-1)).toMatchObject({ status: "verified", token: "one-time-token" });

    act(() => getParameters()?.["expired-callback"]());
    expect(states.at(-1)).toMatchObject({ status: "ready", token: null });

    act(() => getParameters()?.["error-callback"]("network-error"));
    expect(states.at(-1)).toMatchObject({ status: "error", token: null });

    act(() => ref.current?.reset());
    expect(api.reset).toHaveBeenCalledWith("widget-1");
    expect(states.at(-1)).toMatchObject({ status: "ready", token: null });

    unmount();
    expect(api.remove).toHaveBeenCalledWith("widget-1");
  });

  it("SDK 错误后允许用户重新初始化", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ enabled: true, site_key: "site-key" }));
    const { api, getParameters } = installSdk();
    render(<HCaptchaChallenge onStateChange={() => undefined} />);
    await waitFor(() => expect(api.render).toHaveBeenCalledTimes(1));

    act(() => getParameters()?.["error-callback"]("challenge-error"));
    await userEvent.click(screen.getByRole("button", { name: "重新加载人机验证" }));

    await waitFor(() => expect(api.reset).toHaveBeenCalledWith("widget-1"));
  });

  it("SDK reset 抛错时仍清除旧 token 并保持失败关闭", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ enabled: true, site_key: "site-key" }));
    const { api, getParameters } = installSdk();
    vi.mocked(api.reset).mockImplementation(() => {
      throw new Error("invalid widget");
    });
    const states: HCaptchaChallengeState[] = [];
    const ref = createRef<HCaptchaChallengeHandle>();
    render(<HCaptchaChallenge ref={ref} onStateChange={(state) => states.push(state)} />);
    await waitFor(() => expect(api.render).toHaveBeenCalledTimes(1));
    act(() => getParameters()?.callback("must-not-survive-reset"));

    expect(() => act(() => ref.current?.reset())).not.toThrow();

    expect(states.at(-1)).toMatchObject({ status: "error", token: null });
  });

  it("SDK remove 抛错时卸载仍完成且不向 React 清理阶段传播", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ enabled: true, site_key: "site-key" }));
    const { api } = installSdk();
    vi.mocked(api.remove!).mockImplementation(() => {
      throw new Error("remove failed");
    });
    const view = render(<HCaptchaChallenge onStateChange={() => undefined} />);
    await waitFor(() => expect(api.render).toHaveBeenCalledTimes(1));

    expect(() => view.unmount()).not.toThrow();
  });
});

describe("loadHCaptchaSdk", () => {
  afterEach(() => {
    delete (window as HCaptchaWindow).hcaptcha;
    hCaptchaScripts().forEach((script) => script.remove());
  });

  it("并发调用只插入一个官方脚本并等待官方 onload 回调", async () => {
    const first = loadHCaptchaSdk();
    const scripts = hCaptchaScripts();
    expect(scripts).toHaveLength(1);
    expect(new URL(scripts[0].src).searchParams.get("onload")).toBe(HCAPTCHA_ONLOAD_CALLBACK_NAME);

    const api = installSdk().api;
    const second = loadHCaptchaSdk();
    let secondResolvedBeforeOfficialCallback = false;
    void second.then(() => {
      secondResolvedBeforeOfficialCallback = true;
    });
    scripts[0].dispatchEvent(new Event("load"));
    await Promise.resolve();
    expect(secondResolvedBeforeOfficialCallback).toBe(false);

    (window as HCaptchaWindow)[HCAPTCHA_ONLOAD_CALLBACK_NAME]?.();

    await expect(first).resolves.toBe(api);
    await expect(second).resolves.toBe(api);
  });
});
