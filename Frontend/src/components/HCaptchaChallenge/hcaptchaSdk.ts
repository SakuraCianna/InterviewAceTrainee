/** hCaptcha 官方显式渲染脚本地址。 */
export const HCAPTCHA_SCRIPT_SRC = "https://js.hcaptcha.com/1/api.js?render=explicit&recaptchacompat=off";
export const HCAPTCHA_ONLOAD_CALLBACK_NAME = "mianbaHCaptchaSdkReady";

export type HCaptchaWidgetId = string | number;

/**
 * hCaptcha 显式渲染参数的最小本地类型。
 *
 * 仅声明本项目实际使用的字段，避免为一个浏览器全局 SDK 引入运行时依赖。
 */
export type HCaptchaRenderParameters = {
  sitekey: string;
  size: "normal" | "compact";
  callback: (token: string) => void;
  "expired-callback": () => void;
  "error-callback": (errorCode?: string) => void;
};

/** hCaptcha 浏览器 SDK 的最小本地类型。 */
export type HCaptchaApi = {
  render: (container: HTMLElement, parameters: HCaptchaRenderParameters) => HCaptchaWidgetId;
  reset: (widgetId?: HCaptchaWidgetId) => void;
  remove?: (widgetId?: HCaptchaWidgetId) => void;
};

type HCaptchaWindow = Window & {
  hcaptcha?: HCaptchaApi;
  mianbaHCaptchaSdkReady?: () => void;
};

const SCRIPT_ID = "mianba-hcaptcha-sdk";
const SDK_READY_TIMEOUT_MS = 10_000;
let sdkPromise: Promise<HCaptchaApi> | null = null;
let sdkLoadAttempted = false;
let sdkReady = false;

function currentSdk() {
  const sdk = (window as HCaptchaWindow).hcaptcha;
  return sdk && typeof sdk.render === "function" ? sdk : undefined;
}

function scriptSource() {
  return `${HCAPTCHA_SCRIPT_SRC}&onload=${encodeURIComponent(HCAPTCHA_ONLOAD_CALLBACK_NAME)}`;
}

/**
 * 单例加载 hCaptcha SDK。
 *
 * 认证页和管理员页可能在同一个 SPA 生命周期内先后挂载，共享 Promise 可以避免重复脚本和竞态渲染。
 */
export function loadHCaptchaSdk(): Promise<HCaptchaApi> {
  if (sdkPromise) {
    return sdkPromise;
  }
  const sdk = currentSdk();
  if (sdkReady && sdk) {
    return Promise.resolve(sdk);
  }
  if (sdkReady && !sdk) {
    sdkReady = false;
  }
  if (!sdkLoadAttempted && sdk) {
    sdkReady = true;
    return Promise.resolve(sdk);
  }

  sdkLoadAttempted = true;
  sdkPromise = new Promise<HCaptchaApi>((resolve, reject) => {
    const hCaptchaWindow = window as HCaptchaWindow;
    let timeoutId = 0;
    let settled = false;
    let script = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null;
    if (script && script.getAttribute("src") !== scriptSource()) {
      script.remove();
      script = null;
    }

    const cleanup = () => {
      window.clearTimeout(timeoutId);
      if (hCaptchaWindow.mianbaHCaptchaSdkReady === handleReady) {
        delete hCaptchaWindow.mianbaHCaptchaSdkReady;
      }
    };
    const fail = (error: Error) => {
      if (settled) {
        return;
      }
      settled = true;
      sdkReady = false;
      cleanup();
      reject(error);
    };
    const handleReady = () => {
      const readySdk = currentSdk();
      if (!readySdk) {
        fail(new Error("hCaptcha SDK callback fired without an API"));
        return;
      }
      if (settled) {
        return;
      }
      settled = true;
      sdkReady = true;
      cleanup();
      resolve(readySdk);
    };
    const handleError = () => fail(new Error("hCaptcha SDK failed to load"));

    hCaptchaWindow.mianbaHCaptchaSdkReady = handleReady;
    timeoutId = window.setTimeout(
      () => fail(new Error("hCaptcha SDK did not become ready")),
      SDK_READY_TIMEOUT_MS,
    );
    const shouldAppendScript = !script;
    if (!script) {
      script = document.createElement("script");
      script.id = SCRIPT_ID;
      script.src = scriptSource();
      script.async = true;
      script.defer = true;
    }
    script.addEventListener("error", handleError, { once: true });
    if (shouldAppendScript) {
      document.head.append(script);
    }
  }).catch((error: unknown) => {
    sdkPromise = null;
    document.getElementById(SCRIPT_ID)?.remove();
    throw error;
  });

  return sdkPromise;
}
