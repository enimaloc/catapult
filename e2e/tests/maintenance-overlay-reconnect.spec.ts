import { expect, test } from "@playwright/test";
import { startService, stopService, waitForHealthy } from "./helpers/compose";

/**
 * Scenario 3 (spec §12) — once catapult-web is back, the overlay should
 * disappear and a "Connexion rétablie" toast should briefly show.
 */
test.describe("maintenance overlay — reconnect", () => {
  test.skip(
    !!process.env.CATAPULT_E2E_SKIP_DOCKER,
    "requires docker compose access to stop/start catapult-web"
  );

  test("overlay hides and toast appears after catapult-web restart", async ({ page }) => {
    test.setTimeout(180_000);

    await page.goto("/");
    await page.waitForFunction(() => {
      const w = window as unknown as { catapultOverlay?: { _state: () => { wasConnected: boolean } } };
      return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
    }, undefined, { timeout: 15_000 });

    const overlay = page.locator("#maintenance-overlay");
    const toastContainer = page.locator("#toast-container");

    // Drop the server.
    stopService("catapult-web");

    // Wait for the overlay to appear.
    await expect(async () => {
      const hidden = await overlay.getAttribute("hidden");
      expect(hidden).toBeNull();
    }).toPass({ timeout: 60_000, intervals: [500, 1000, 2000] });

    // Bring it back.
    startService("catapult-web");
    waitForHealthy("catapult-web", 90_000);

    // Wait for the watchdog → reconnect cycle to complete.
    await expect(async () => {
      const hidden = await overlay.getAttribute("hidden");
      expect(hidden).toBe("");
    }).toPass({ timeout: 60_000, intervals: [500, 1000, 2000] });

    // A success toast should have been rendered (it self-removes after ~3s,
    // so we use waitFor with state:"visible" and a short timeout).
    await expect(toastContainer.locator(".toast.toast-success")).toHaveText(
      /rétablie/i,
      { timeout: 5_000 }
    );
  });
});
