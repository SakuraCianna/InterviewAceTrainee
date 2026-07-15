import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import { getHCaptchaConfig } from "../../lib/api/hcaptcha";
import styles from "./HCaptchaChallenge.module.css";
import {
  loadHCaptchaSdk,
  type HCaptchaApi,
  type HCaptchaWidgetId,
} from "./hcaptchaSdk";

export type HCaptchaChallengeStatus = "loading" | "disabled" | "ready" | "verified" | "error";

/** 调用方只持有是否可提交与一次性 token，不接触 SDK 实例。 */
export type HCaptchaChallengeState = {
  status: HCaptchaChallengeStatus;
  enabled: boolean | null;
  token: string | null;
};

export const INITIAL_HCAPTCHA_STATE: HCaptchaChallengeState = {
  status: "loading",
  enabled: null,
  token: null,
};

export type HCaptchaChallengeHandle = {
  reset: () => void;
};

export type HCaptchaChallengeProps = {
  label?: string;
  onStateChange: (state: HCaptchaChallengeState) => void;
};

/**
 * 返回受保护请求应携带的 token。
 *
 * 空字符串仅表示服务端明确关闭 hCaptcha；配置加载和错误状态均返回 null，以便调用方保持失败关闭。
 */
export function hCaptchaTokenForRequest(state: HCaptchaChallengeState) {
  if (state.status === "disabled") {
    return "";
  }
  if (state.status === "verified" && state.token) {
    return state.token;
  }
  return null;
}

function challengeMessage(status: HCaptchaChallengeStatus) {
  switch (status) {
    case "loading":
      return "正在加载人机验证";
    case "ready":
      return "请完成人机验证后继续";
    case "verified":
      return "人机验证已完成，本次结果仅用于下一次受保护请求";
    case "error":
      return "人机验证暂不可用，请重新加载";
    case "disabled":
      return "";
  }
}

/**
 * 可复用的 hCaptcha 显式渲染组件。
 *
 * token 仅保存在组件与调用方内存中，请求完成后必须通过 ref 重置，防止一次性 token 被重复使用。
 */
export const HCaptchaChallenge = forwardRef<HCaptchaChallengeHandle, HCaptchaChallengeProps>(
  function HCaptchaChallenge({ label = "安全验证", onStateChange }, ref) {
    const containerRef = useRef<HTMLDivElement | null>(null);
    const apiRef = useRef<HCaptchaApi | null>(null);
    const widgetIdRef = useRef<HCaptchaWidgetId | null>(null);
    const [state, setState] = useState<HCaptchaChallengeState>(INITIAL_HCAPTCHA_STATE);
    const [initializationAttempt, setInitializationAttempt] = useState(0);

    const publishState = useCallback((nextState: HCaptchaChallengeState) => {
      setState(nextState);
      onStateChange(nextState);
    }, [onStateChange]);

    const reset = useCallback(() => {
      const api = apiRef.current;
      const widgetId = widgetIdRef.current;
      if (api && widgetId !== null) {
        publishState({ status: "ready", enabled: true, token: null });
        try {
          api.reset(widgetId);
        } catch {
          publishState({ status: "error", enabled: true, token: null });
        }
        return;
      }
      if (state.status !== "disabled") {
        publishState({ status: "loading", enabled: state.enabled, token: null });
        setInitializationAttempt((attempt) => attempt + 1);
      }
    }, [publishState, state.status]);

    useImperativeHandle(ref, () => ({ reset }), [reset]);

    useEffect(() => {
      const abortController = new AbortController();
      let active = true;
      publishState(INITIAL_HCAPTCHA_STATE);

      async function initialize() {
        try {
          const { response, data } = await getHCaptchaConfig(abortController.signal);
          if (!active) {
            return;
          }
          if (!response.ok) {
            throw new Error("hCaptcha configuration request failed");
          }
          if (data.enabled === false) {
            publishState({ status: "disabled", enabled: false, token: null });
            return;
          }

          const siteKey = typeof data.site_key === "string" ? data.site_key.trim() : "";
          if (!siteKey) {
            throw new Error("hCaptcha site key is missing");
          }

          const api = await loadHCaptchaSdk();
          if (!active || !containerRef.current) {
            return;
          }
          apiRef.current = api;
          const widgetId = api.render(containerRef.current, {
            sitekey: siteKey,
            size: window.matchMedia("(max-width: 380px)").matches ? "compact" : "normal",
            callback: (token) => {
              if (active && token) {
                publishState({ status: "verified", enabled: true, token });
              }
            },
            "expired-callback": () => {
              if (active) {
                publishState({ status: "ready", enabled: true, token: null });
              }
            },
            "error-callback": () => {
              if (active) {
                publishState({ status: "error", enabled: true, token: null });
              }
            },
          });
          widgetIdRef.current = widgetId;
          publishState({ status: "ready", enabled: true, token: null });
        } catch {
          if (active && !abortController.signal.aborted) {
            publishState({ status: "error", enabled: true, token: null });
          }
        }
      }

      void initialize();
      return () => {
        active = false;
        abortController.abort();
        const api = apiRef.current;
        const widgetId = widgetIdRef.current;
        try {
          if (api && widgetId !== null) {
            if (api.remove) {
              api.remove(widgetId);
            } else {
              api.reset(widgetId);
            }
          }
        } catch {
          // 第三方 SDK 清理失败不能中断 React 卸载；finally 仍会释放本地 DOM 与引用。
        } finally {
          containerRef.current?.replaceChildren();
          apiRef.current = null;
          widgetIdRef.current = null;
        }
      };
    }, [initializationAttempt, publishState]);

    if (state.status === "disabled") {
      return null;
    }

    return (
      <section className={styles.challenge} aria-label={label}>
        <span className={styles.label}>{label}</span>
        <div className={styles.widgetViewport} ref={containerRef} />
        <p
          className={`${styles.status}${state.status === "error" ? ` ${styles.statusError}` : ""}`}
          role="status"
          aria-live="polite"
        >
          {challengeMessage(state.status)}
        </p>
        {state.status === "error" && (
          <button className={styles.retry} type="button" onClick={reset}>
            重新加载人机验证
          </button>
        )}
      </section>
    );
  },
);
