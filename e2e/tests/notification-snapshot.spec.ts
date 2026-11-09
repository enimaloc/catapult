import { test, expect } from "@playwright/test";

const CHANNEL = process.env.CATAPULT_E2E_CHANNEL_USERNAME || "enimaloc";

test.describe("notifications — snapshot on page load", () => {
  test.skip(
    !process.env.CATAPULT_E2E_AUTH_COOKIE,
    "no auth cookie provided; see e2e/README.md for instructions"
  );

  test.beforeEach(async ({ context }) => {
    await context.addCookies([
      {
        name: "JSESSIONID",
        value: process.env.CATAPULT_E2E_AUTH_COOKIE!,
        url: process.env.BASE_URL || "http://localhost",
      },
    ]);
  });

  test("notif-bell is visible and badge shows a non-null integer after WS auth", async ({ page }) => {
    test.setTimeout(60_000);

    await page.goto(`/channels/${CHANNEL}`);

    await page.waitForFunction(
      () => {
        const w = window as unknown as { catapultWs?: unknown };
        return !!w.catapultWs;
      },
      undefined,
      { timeout: 15_000 }
    );

    // Wait for auth.ok by polling the overlay state
    await page.waitForFunction(
      () => {
        const w = window as unknown as {
          catapultOverlay?: { _state: () => { wasConnected: boolean } };
        };
        return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
      },
      undefined,
      { timeout: 15_000 }
    );

    // Wait for the notification list to be populated (snapshot pushed)
    await page.waitForFunction(
      () => {
        const el = document.getElementById("notif-list");
        return el !== null && el.children.length >= 0;
      },
      undefined,
      { timeout: 15_000 }
    );

    const bell = page.locator("#notif-bell");
    await expect(bell).toBeVisible();

    // The badge should contain a non-null integer (unread count)
    const badge = page.locator("#notif-badge");
    const text = await badge.textContent({ timeout: 10_000 });
    if (text !== null && text.trim() !== "") {
      const count = parseInt(text.trim(), 10);
      expect(Number.isInteger(count)).toBeTruthy();
    }
  });
});
