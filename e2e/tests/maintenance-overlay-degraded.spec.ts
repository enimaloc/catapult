import { expect, test } from "@playwright/test";
import { startService, stopService, waitForHealthy } from "./helpers/compose";

/**
 * Scenario 2 (spec §12) — kill catapult-web, the overlay must appear within
 * ~50s (15s server ping × 2 missed + reconnect attempt fails fast).
 *
 * The overlay is owned by maintenance-overlay.js, which listens to ws:degraded
 * / ws:closed from ws-client.js. Closing the upstream socket from the server
 * side triggers `onclose` on the client → `ws:closed` → overlay degraded.
 */
test.describe("maintenance overlay — degraded", () => {
  test.skip(
    !!process.env.CATAPULT_E2E_SKIP_DOCKER,
    "requires docker compose access to stop catapult-web"
  );

  test.afterEach(async () => {
    // Best effort recovery so the rest of the suite finds the stack up.
    try {
      startService("catapult-web");
      waitForHealthy("catapult-web", 60_000);
    } catch {
      /* ignore */
    }
  });

  test("overlay appears within 50s after catapult-web stops", async ({ page }) => {
    test.setTimeout(120_000);

    await page.goto("/");
    // Wait for ws-client.js to actually open the socket.
    await page.waitForFunction(() => {
      const w = window as unknown as { catapultOverlay?: { _state: () => { wasConnected: boolean } } };
      return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
    }, undefined, { timeout: 15_000 });

    const overlay = page.locator("#maintenance-overlay");
    await expect(overlay).toHaveAttribute("hidden", "");

    // Take catapult-web down. The router 502s subsequent /ws hits and the
    // open socket terminates, both surface as `onclose` on the client.
    stopService("catapult-web");

    // Overlay should become visible (hidden attribute removed) within ~50s.
    // The watchdog timeout is 45s, plus a few seconds for reconnect failure.
    await expect(async () => {
      const hidden = await overlay.getAttribute("hidden");
      expect(hidden).toBeNull();
    }).toPass({ timeout: 60_000, intervals: [500, 1000, 2000] });

    const cause = await overlay.locator(".overlay-card").getAttribute("data-cause");
    expect(["degraded", "unreachable", "shutdown"]).toContain(cause);
  });
});
