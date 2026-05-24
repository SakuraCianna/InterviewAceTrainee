import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: {
    timeout: 10_000,
  },
  use: {
    baseURL: "http://127.0.0.1:5173",
    trace: "on-first-retry",
  },
  webServer: [
    {
      command: "cd ../Backend && uv run uvicorn app.main:app --host 127.0.0.1 --port 8000",
      env: {
        ...process.env,
        EMAIL_PROVIDER: "dev",
        AUTH_COOKIE_SECURE: "false",
        CORS_ORIGINS: "http://127.0.0.1:5173,http://localhost:5173",
      },
      reuseExistingServer: true,
      timeout: 120_000,
      url: "http://127.0.0.1:8000/api/health",
    },
    {
      command: "npm run dev -- --host 127.0.0.1",
      reuseExistingServer: true,
      timeout: 120_000,
      url: "http://127.0.0.1:5173",
    },
  ],
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
