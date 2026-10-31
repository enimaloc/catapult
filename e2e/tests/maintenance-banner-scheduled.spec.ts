import { expect, test } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

/**
 * Scenario 4 (spec §12) — `maintenance.scheduled` broadcast (≥ 5min away)
 * surfaces the preventive banner with a countdown.
 *
 * We publish directly to the Redis bus (bypassing the admin REST endpoint
 * which would require an authenticated admin session).
 */
test.describe("maintenance banner — scheduled broadcast", () => {
  test.skip(
    !!process.env.CATAPULT_E2E_SKIP_DOCKER,
    "requires docker compose access to publish to Redis"
  );

  test("banner appears after maintenance.scheduled event with countdown", async ({ page }) => {
    test.setTimeout(60_000);

    await page.goto("/");

    // Wait for the WS to be open and the banner module bound.
    await page.waitForFunction(() => {
      const w = window as unknown as { catapultOverlay?: { _state: () => { wasConnected: boolean } } };
      return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
    }, undefined, { timeout: 15_000 });

    // Subscribe explicitly to events.global so we receive broadcasts even
    // without an authenticated session (this channel is anon-allowed).
    await page.evaluate(() => {
      const w = window as unknown as {
        catapultWs: { subscribe: (ch: string, cb?: (m: unknown) => void) => void };
      };
      w.catapultWs.subscribe("events.global");
    });

    // Schedule 30min in the future so the banner (window > 5min) is the one
    // that renders, not the overlay.
    const startsAt = new Date(Date.now() + 30 * 60 * 1000).toISOString();
    publishRedis(
      "catapult:events:global",
      JSON.stringify({
        name: "maintenance.scheduled",
        data: {
          startsAt,
          durationMinutes: 15,
          message: "E2E test broadcast",
        },
        ts: Date.now(),
      })
    );

    const banner = page.locator("#maintenance-banner");
    await expect(async () => {
      const hidden = await banner.getAttribute("hidden");
      expect(hidden).toBeNull();
    }).toPass({ timeout: 15_000, intervals: [200, 500] });

    await expect(banner.locator("#b-countdown")).toContainText(/dans \d+/);
    await expect(banner.locator("#b-message")).toContainText(/E2E test broadcast/);
  });
});
