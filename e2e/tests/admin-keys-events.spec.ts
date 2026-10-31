import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

// Admin API-key pages update in place from the `events.admin` channel instead
// of the old full-page POST → redirect reload. We assert the DOM reacts to an
// injected Redis event, mirroring connection-events.spec.ts.

const ADMIN_CHANNEL = "catapult:events:admin";

function envelope(name: string, data: Record<string, unknown>): string {
  return JSON.stringify({ name, data, ts: Date.now() });
}

test.describe("admin steam-keys — live events", () => {
  test.skip(
    !process.env.CATAPULT_E2E_ADMIN_COOKIE,
    "no admin auth cookie provided; set CATAPULT_E2E_ADMIN_COOKIE"
  );

  test.beforeEach(async ({ context }) => {
    await context.addCookies([
      {
        name: "JSESSIONID",
        value: process.env.CATAPULT_E2E_ADMIN_COOKIE!,
        url: process.env.BASE_URL || "http://localhost",
      },
    ]);
  });

  async function gotoAndWaitWs(page: import("@playwright/test").Page): Promise<void> {
    await page.goto("/admin/steam-keys");
    await page.waitForFunction(
      () => {
        const w = window as unknown as {
          catapultOverlay?: { _state: () => { wasConnected: boolean } };
        };
        return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
      },
      undefined,
      { timeout: 20_000 }
    );
  }

  test("steam.key.added inserts a row, steam.key.deleted removes it — no HTTP reload", async ({ page }) => {
    test.setTimeout(60_000);
    await gotoAndWaitWs(page);

    const navigations: string[] = [];
    page.on("framenavigated", (f) => navigations.push(f.url()));

    const keyId = "e2e-key-" + Date.now();
    publishRedis(
      ADMIN_CHANNEL,
      envelope("steam.key.added", {
        key: { id: keyId, masked: "1234…abcd", owner: null, blocked: false, blockedForSeconds: 0 },
      })
    );

    const row = page.locator(`tr[data-key-id="${keyId}"]`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toContainText("1234…abcd");

    publishRedis(ADMIN_CHANNEL, envelope("steam.key.deleted", { keyId }));
    await expect(row).toHaveCount(0, { timeout: 10_000 });

    // The whole interaction happened over WS: no navigation away from the page.
    expect(navigations).toHaveLength(0);
  });
});
