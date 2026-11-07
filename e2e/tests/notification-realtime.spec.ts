import { test, expect } from "@playwright/test";

/**
 * Scenario 1 (spec §12) — authenticated user receives a notification in
 * real time via WS.
 *
 * Auth requires the full Twitch OAuth2 flow (no dev-login endpoint), which
 * is out of reach for a hermetic Playwright run. This spec is therefore
 * SKIPPED by default; set CATAPULT_E2E_AUTH_COOKIE to a valid JSESSIONID
 * (copy from a logged-in browser session) to run it locally.
 */
test.describe("notifications — real-time push", () => {
  test.skip(
    !process.env.CATAPULT_E2E_AUTH_COOKIE,
    "no auth cookie provided; see e2e/README.md for instructions"
  );

  test("badge updates on notification.created event", async ({ context, page }) => {
    test.setTimeout(60_000);

    await context.addCookies([
      {
        name: "JSESSIONID",
        value: process.env.CATAPULT_E2E_AUTH_COOKIE!,
        url: process.env.BASE_URL || "http://localhost",
      },
    ]);

    await page.goto("/");

    // Wait until auth.ok arrives.
    await page.waitForEvent("websocket", { timeout: 10_000 });
    await page.waitForFunction(() => {
      const w = window as unknown as { catapultOverlay?: { _state: () => { wasConnected: boolean } } };
      return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
    }, undefined, { timeout: 15_000 });

    // The actual notification creation has to be triggered server-side; we
    // assume a separate REST call from outside this test (e.g. by an admin
    // helper script) since the WS handler is push-only. Once that happens,
    // the badge in the navbar should reflect the new unread count.
    const badge = page.locator("#notif-badge");
    await expect(badge).toBeVisible({ timeout: 30_000 });
  });
});
