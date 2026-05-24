import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, type APIRequestContext, test } from "@playwright/test";

function base64Url(value: Buffer | string) {
  return Buffer.from(value).toString("base64url");
}

function readBackendEnv(name: string, fallback: string) {
  const envPath = resolve(process.cwd(), "../Backend/.env");
  const text = readFileSync(envPath, "utf-8");
  const line = text.split(/\r?\n/).find((item) => item.startsWith(`${name}=`));
  return line?.slice(name.length + 1).trim() || fallback;
}

function createTestAccessToken(email: string, role: "admin" | "user") {
  const secret = readBackendEnv("ACCESS_TOKEN_SECRET", "change-me-before-deploy");
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = base64Url(JSON.stringify({ sub: email, role, iat: now, exp: now + 3600 }));
  const signature = createHmac("sha256", secret).update(`${header}.${payload}`).digest("base64url");
  return `${header}.${payload}.${signature}`;
}

async function grantCredits(request: APIRequestContext, adminToken: string, userEmail: string, amount: number) {
  const response = await request.post(`/api/admin/users/${encodeURIComponent(userEmail)}/credits`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { change_amount: amount, reason: "manual_grant" },
  });
  expect(response.status()).toBe(200);
}

test("user can register, receive credits, start and review a job interview", async ({ page, request }) => {
  const email = `e2e-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;

  await page.goto("/register");
  await page.locator('input[type="email"]').fill(email);
  await page.locator(".code-button").click();
  await expect(page.locator(".auth-message")).toContainText(/验证码|code/i);
  await expect(page.locator('input[placeholder="6 位验证码"]')).toHaveValue(/^\d{6}$/);
  await page.locator('input[type="password"]').fill("StrongPass123");
  const registerResponsePromise = page.waitForResponse(
    (response) => response.url().includes("/api/auth/password/register") && response.request().method() === "POST",
  );
  await page.locator("button.auth-submit").click();
  const registerResponse = await registerResponsePromise;
  expect(registerResponse.status()).toBe(201);
  await expect(page).toHaveURL(/\/interview$/);

  const adminToken = createTestAccessToken("e2e-admin@example.com", "admin");
  await grantCredits(request, adminToken, email, 3);

  await page.locator('input[type="file"]').setInputFiles({
    name: "resume.txt",
    mimeType: "text/plain",
    buffer: Buffer.from(
      "姓名：测试用户\n项目：基于 FastAPI 和 React 的面试训练平台，负责后端接口、用户认证、PostgreSQL 持久化和日志审计。\n成果：完成可恢复训练流程和复盘报告。",
      "utf-8",
    ),
  });
  await page.locator(".material-form input").nth(1).fill("AI 后端工程师");
  await page
    .locator(".material-form textarea")
    .fill("负责 FastAPI、PostgreSQL、Redis、Docker Compose 和大模型接口集成，要求能处理认证、日志、模型路由和线上故障排查。");
  await page.locator(".material-actions button").click();

  await expect(page.locator(".material-preview")).toBeVisible();
  await expect(page.locator(".voice-controls button").first()).toBeEnabled();

  await page.locator(".voice-controls button").first().click();
  await expect(page.locator(".socket-status")).toContainText(/面试|训练|次数/);

  const contextRequest = page.context().request;
  await expect
    .poll(
      async () => {
        const response = await contextRequest.get("/api/interviews/active");
        return response.status();
      },
      { timeout: 15_000 },
    )
    .toBe(200);
  let activeResponse = await contextRequest.get("/api/interviews/active");
  expect(activeResponse.status()).toBe(200);
  let active = await activeResponse.json();
  const csrfToken = (await page.context().cookies()).find((cookie) => cookie.name === "mianba_csrf_token")?.value;
  expect(csrfToken).toBeTruthy();

  for (let index = 0; index < active.total_steps && active.status !== "completed"; index += 1) {
    const answerResponse = await contextRequest.post(`/api/interviews/${encodeURIComponent(active.session_id)}/answers`, {
      headers: { "X-CSRF-Token": csrfToken ?? "" },
      data: {
        answer_text:
          "我会先说明项目背景，再讲我的职责、关键技术取舍、量化结果和复盘改进，保证回答能支撑岗位匹配。",
      },
    });
    expect(answerResponse.status()).toBe(200);
    active = await answerResponse.json();
  }
  expect(active.status).toBe("completed");
  expect(active.report.total_score).toBeGreaterThanOrEqual(60);

  await page.reload();
  await expect(page.locator(".history-list button").first()).toBeVisible();
  await page.locator(".history-list button").first().click();
  await expect(page.locator(".room-report")).toBeVisible();
});
