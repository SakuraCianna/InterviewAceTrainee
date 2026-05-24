import { expect, test } from "@playwright/test";

test("user can register and prepare job interview material", async ({ page }) => {
  const email = `e2e-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;

  await page.goto("/register");
  await page.locator('input[type="email"]').fill(email);
  await page.locator(".code-button").click();
  await expect(page.locator(".auth-message")).toContainText(/验证码|code/i);
  await page.locator('input[type="password"]').fill("StrongPass123");
  await page.locator("button.auth-submit").click();
  await expect(page).toHaveURL(/\/interview$/);

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
});
