import { afterEach, describe, expect, it, vi } from "vitest";
import { loginAdmin, requestAdminEmailCode } from "./adminApi";

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("管理员认证 API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("请求邮箱验证码时携带一次性 hCaptcha token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({}));
    vi.stubGlobal("fetch", fetchMock);

    await requestAdminEmailCode("admin@example.com", "captcha-code-token");

    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(request.body))).toEqual({
      email: "admin@example.com",
      captcha_token: "captcha-code-token",
    });
  });

  it("管理员登录时携带新的 hCaptcha token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ email: "admin@example.com", role: "admin" }));
    vi.stubGlobal("fetch", fetchMock);

    await loginAdmin("admin@example.com", "password-123", "123456", "captcha-login-token");

    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(request.body))).toEqual({
      email: "admin@example.com",
      password: "password-123",
      code: "123456",
      captcha_token: "captcha-login-token",
    });
  });
});
