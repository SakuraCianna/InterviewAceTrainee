import { requestJson } from "./client";

/** 服务端公开的 hCaptcha 展示配置，不包含服务端验证密钥。 */
export type HCaptchaConfig = {
  enabled: boolean;
  site_key: string;
};

/** 读取 hCaptcha 开关与公开站点密钥；失败时由调用方保持关闭提交。 */
export function getHCaptchaConfig(signal?: AbortSignal) {
  return requestJson<HCaptchaConfig>(
    "/api/auth/hcaptcha/config",
    {
      credentials: "include",
      csrf: false,
      handleUnauthorized: false,
      signal,
      timeoutMs: 8_000,
    },
    { enabled: true, site_key: "" },
  );
}
