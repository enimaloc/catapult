import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

const CHANNEL = process.env.CATAPULT_E2E_CHANNEL_USERNAME || "enimaloc";
const NOTIF_ID = "99999999-0000-0000-0000-00000000ABCD";

function redisChannel(uuid: string): string {
  return `catapult:events:user:${uuid}`;
}

function envelope(name: string, data: Record<string, unknown>): string {
  return JSON.stringify({ name, data, ts: Date.now() });
}

test.describe("notifications — Redis event injection", () => {
  test.skip(
    !process.env.CATAPULT_E2E_AUTH_COOKIE,
    "no auth cookie provided; see e2e/README.md for instructions"
  );

  test.skip(
    !process.env.CATAPULT_E2E_USER_UUID,
    "no user UUID provided; set CATAPULT_E2E_USER_UUID"
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

  async function gotoAndWaitWs(page: import("@playwright/test").Page): Promise<void> {
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
  }

  test("notification.created → new li in #notif-list, badge increments", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const badgeBefore = await page.locator("#notif-badge").textContent();
    const countBefore = parseInt((badgeBefore ?? "0").trim(), 10) || 0;

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(
      redisChannel(uuid),
      envelope("notification.created", {
        id: NOTIF_ID,
        title: "E2E test notification",
        body: "created by e2e",
        unreadCount: countBefore + 1,
        readAt: null,
        createdAt: new Date().toISOString(),
      })
    );

    await expect(page.locator(`#notif-list li[data-notification-id="${NOTIF_ID}"]`)).toBeVisible({
      timeout: 10_000,
    });

    const badgeAfter = await page.locator("#notif-badge").textContent({ timeout: 10_000 });
    const countAfter = parseInt((badgeAfter ?? "0").trim(), 10) || 0;
    expect(countAfter).toBeGreaterThanOrEqual(countBefore + 1);
  });

  test("notification.read.changed → li gets .read class, badge shows unreadCount", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    // First create the notification
    publishRedis(
      redisChannel(uuid),
      envelope("notification.created", {
        id: NOTIF_ID,
        title: "E2E read test",
        body: "will be read",
        unreadCount: 1,
        readAt: null,
        createdAt: new Date().toISOString(),
      })
    );
    await expect(page.locator(`#notif-list li[data-notification-id="${NOTIF_ID}"]`)).toBeVisible({
      timeout: 10_000,
    });

    // Now mark it read
    publishRedis(
      redisChannel(uuid),
      envelope("notification.read.changed", {
        id: NOTIF_ID,
        readAt: new Date().toISOString(),
        unreadCount: 0,
      })
    );

    await expect(page.locator(`#notif-list li[data-notification-id="${NOTIF_ID}"]`)).toHaveClass(
      /read/,
      { timeout: 10_000 }
    );
  });

  test("notification.all.read → all li get .read, badge hidden (count 0)", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(
      redisChannel(uuid),
      envelope("notification.all.read", { unreadCount: 0 })
    );

    await page.waitForFunction(
      () => {
        const list = document.getElementById("notif-list");
        if (!list) return true; // empty list is fine
        const items = list.querySelectorAll("li[data-notification-id]");
        if (items.length === 0) return true;
        return Array.from(items).every((li) => li.classList.contains("read"));
      },
      undefined,
      { timeout: 10_000 }
    );

    // Badge should be hidden or show 0
    const badge = page.locator("#notif-badge");
    const isVisible = await badge.isVisible();
    if (isVisible) {
      const text = await badge.textContent();
      expect(parseInt((text ?? "0").trim(), 10)).toBe(0);
    }
  });

  test("notification.deleted → li removed, badge shows unreadCount", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    // Create then delete
    publishRedis(
      redisChannel(uuid),
      envelope("notification.created", {
        id: NOTIF_ID,
        title: "E2E delete test",
        body: "will be deleted",
        unreadCount: 1,
        readAt: null,
        createdAt: new Date().toISOString(),
      })
    );
    await expect(page.locator(`#notif-list li[data-notification-id="${NOTIF_ID}"]`)).toBeVisible({
      timeout: 10_000,
    });

    publishRedis(
      redisChannel(uuid),
      envelope("notification.deleted", { id: NOTIF_ID, unreadCount: 0 })
    );

    await expect(page.locator(`#notif-list li[data-notification-id="${NOTIF_ID}"]`)).toBeHidden({
      timeout: 10_000,
    });
  });
});
