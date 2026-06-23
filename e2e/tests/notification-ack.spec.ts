import { test, expect } from "@playwright/test";

/**
 * Scenario 6 (spec §12) — ack a notification via WS (`command notification.read`)
 * and check `readAt` is set in the DB / unread count decrements.
 *
 * Auth requires the full Twitch OAuth2 flow (no dev-login endpoint), which
 * is out of reach for a hermetic Playwright run. This spec is therefore
 * SKIPPED by default; set CATAPULT_E2E_AUTH_COOKIE to a valid JSESSIONID
 * (copy from a logged-in browser session) to run it locally.
 */
test.describe("notifications — ack via WS", () => {
  test.skip(
    !process.env.CATAPULT_E2E_AUTH_COOKIE,
    "no auth cookie provided; see e2e/README.md for instructions"
  );

  test("notification.read command decrements unread count", async ({ context, page }) => {
    test.setTimeout(60_000);

    await context.addCookies([
      {
        name: "JSESSIONID",
        value: process.env.CATAPULT_E2E_AUTH_COOKIE!,
        url: process.env.BASE_URL || "http://localhost",
      },
    ]);

    await page.goto("/notifications");
    await page.waitForFunction(() => {
      const w = window as unknown as { catapultOverlay?: { _state: () => { wasConnected: boolean } } };
      return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
    }, undefined, { timeout: 15_000 });

    // Grab the id of the first unread notification — depends on the data
    // fixture in the test environment.
    const firstUnread = page.locator("[data-notification-id][data-unread='true']").first();
    await expect(firstUnread).toBeVisible({ timeout: 10_000 });
    const id = await firstUnread.getAttribute("data-notification-id");
    expect(id).toBeTruthy();

    const badgeBefore = await page.locator("#notif-badge").textContent();
    const before = parseInt((badgeBefore || "0").trim(), 10);

    await page.evaluate((nid) => {
      const w = window as unknown as {
        catapultWs: { command: (action: string, params: object) => void };
      };
      w.catapultWs.command("notification.read", { notificationId: nid });
    }, id);

    await expect(async () => {
      const after = parseInt(
        ((await page.locator("#notif-badge").textContent()) || "0").trim(),
        10
      );
      expect(after).toBeLessThan(before);
    }).toPass({ timeout: 10_000, intervals: [500, 1000] });
  });
});
