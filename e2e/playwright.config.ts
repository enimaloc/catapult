import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config for the catapult WebSocket E2E suite.
 *
 * Assumes the docker compose stack is up and reachable at `BASE_URL`
 * (default: http://localhost, the nginx-router published port).
 *
 *   docker compose up -d
 *   cd e2e && npx playwright test
 *
 * Scenarios that interact with `docker compose` (kill/restart/SIGTERM
 * `catapult-web`) require docker access from the host running the tests.
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false, // we mutate the shared compose stack — keep tests sequential
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["line"], ["html", { open: "never" }]] : "list",
  timeout: 90_000,
  expect: {
    timeout: 10_000,
  },
  use: {
    baseURL: process.env.BASE_URL || "http://localhost",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
