import { expect, test } from "@playwright/test";
import { startService, stopService, waitForHealthy } from "./helpers/compose";

/**
 * Scenario 7 (spec §12) — `docker compose stop catapult-web` sends SIGTERM.
 * The `GracefulShutdownBroadcaster` reacts to `ContextClosedEvent` and pushes
 * `maintenance.imminent {reason:"shutdown", etaSeconds:10}` directly through
 * the hub before the JVM exits. The overlay must switch to the shutdown
 * variant (amber spinner + countdown).
 */
test.describe("shutdown overlay — SIGTERM catapult-web", () => {
  test.skip(
    !!process.env.CATAPULT_E2E_SKIP_DOCKER,
    "requires docker compose access to send SIGTERM to catapult-web"
  );

  test.afterEach(async () => {
    try {
      startService("catapult-web");
      waitForHealthy("catapult-web", 90_000);
    } catch {
      /* ignore */
    }
  });

  test("graceful shutdown shows shutdown overlay with countdown", async ({ page }) => {
    test.setTimeout(120_000);

    await page.goto("/");
    await page.waitForFunction(() => {
      const w = window as unknown as { catapultOverlay?: { _state: () => { wasConnected: boolean } } };
      return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
    }, undefined, { timeout: 15_000 });

    // Subscribe to events.global so we receive maintenance.imminent.
    await page.evaluate(() => {
      const w = window as unknown as {
        catapultWs: { subscribe: (ch: string, cb?: (m: unknown) => void) => void };
      };
      w.catapultWs.subscribe("events.global");
    });

    const overlay = page.locator("#maintenance-overlay");
    const card = overlay.locator(".overlay-card");
    const countdown = overlay.locator("#m-countdown");

    // SIGTERM → graceful shutdown hook fires → maintenance.imminent frame.
    stopService("catapult-web");

    // The shutdown sticky window is 10s — within that window the variant
    // must be `shutdown`, not `degraded`.
    await expect(async () => {
      const hidden = await overlay.getAttribute("hidden");
      expect(hidden).toBeNull();
      const cause = await card.getAttribute("data-cause");
      expect(cause).toBe("shutdown");
    }).toPass({ timeout: 20_000, intervals: [200, 500, 1000] });

    // Countdown must render and decrement.
    await expect(countdown).toBeVisible();
    await expect(countdown).toContainText(/\d+s/);
  });
});
