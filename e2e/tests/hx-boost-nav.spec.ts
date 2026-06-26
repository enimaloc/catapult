import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

const CHANNEL = process.env.CATAPULT_E2E_CHANNEL_USERNAME || "enimaloc";

declare global {
  interface Window {
    __beforeBoost: {
      ws: typeof window.catapultWs;
      bell: Element | null;
    };
  }
}

test.describe("hx-boost navigation — WS identity preserved", () => {
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

  test("catapultWs and notif-bell are the same objects after hx-boost nav", async ({ page }) => {
    test.setTimeout(60_000);

    await page.goto(`/channels/${CHANNEL}`);

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

    // Store references before boost
    await page.evaluate(() => {
      window.__beforeBoost = {
        ws: window.catapultWs,
        bell: document.getElementById("notif-bell"),
      };
    });

    // Trigger hx-boost navigation via navbar link
    await page.locator('nav a[href="/admin/notifications"]').first().click();
    await page.waitForURL("**/admin/notifications", { timeout: 15_000 });

    // WS reference must be identical (same object, not re-created)
    const sameWs = await page.evaluate(
      () => window.catapultWs === window.__beforeBoost.ws
    );
    expect(sameWs).toBe(true);

    // notif-bell must be the same DOM element (hx-preserve)
    const sameBell = await page.evaluate(
      () => document.getElementById("notif-bell") === window.__beforeBoost.bell
    );
    expect(sameBell).toBe(true);
  });

  test("WS subscription still works after hx-boost nav (badge updates)", async ({ page }) => {
    test.setTimeout(60_000);

    test.skip(
      !process.env.CATAPULT_E2E_USER_UUID,
      "no user UUID provided; set CATAPULT_E2E_USER_UUID"
    );

    await page.goto(`/channels/${CHANNEL}`);

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

    // Navigate away via hx-boost
    await page.locator('nav a[href="/admin/notifications"]').first().click();
    await page.waitForURL("**/admin/notifications", { timeout: 15_000 });

    const badgeBefore = await page.locator("#notif-badge").textContent();
    const countBefore = parseInt((badgeBefore ?? "0").trim(), 10) || 0;

    // Push a notification via Redis after the boost
    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(
      `catapult:events:user:${uuid}`,
      JSON.stringify({
        name: "notification.created",
        data: {
          id: "99999999-0000-0000-0000-BOOST000001",
          title: "E2E post-boost notification",
          body: "sent after hx-boost nav",
          unreadCount: countBefore + 1,
          readAt: null,
          createdAt: new Date().toISOString(),
        },
        ts: Date.now(),
      })
    );

    // Badge should increment, proving WS subscription survived the navigation
    await expect(async () => {
      const text = await page.locator("#notif-badge").textContent();
      const after = parseInt((text ?? "0").trim(), 10) || 0;
      expect(after).toBeGreaterThan(countBefore);
    }).toPass({ timeout: 10_000, intervals: [500, 1000] });
  });
});
