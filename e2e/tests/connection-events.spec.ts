import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

const CHANNEL = process.env.CATAPULT_E2E_CHANNEL_USERNAME || "enimaloc";

function redisChannel(uuid: string): string {
  return `catapult:events:channel:${uuid}`;
}

function envelope(name: string, data: Record<string, unknown>): string {
  return JSON.stringify({ name, data, ts: Date.now() });
}

test.describe("channel page — connection events", () => {
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

  test("connection.changed STEAM disconnected → disconnected block visible, connected block hidden", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(
      redisChannel(uuid),
      envelope("connection.changed", {
        provider: "STEAM",
        connected: false,
        profile: null,
      })
    );

    await expect(page.locator('[data-conn-block="steam-disconnected"]')).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.locator('[data-conn-block="steam-connected"]')).toBeHidden({
      timeout: 10_000,
    });
    await expect(page.locator('[data-conn-block="steam-connect-link"]')).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.locator('[data-conn-block="steam-disconnect-form"]')).toBeHidden({
      timeout: 10_000,
    });
  });

  test("connection.changed STEAM connected → connected block visible, disconnected block hidden", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(
      redisChannel(uuid),
      envelope("connection.changed", {
        provider: "STEAM",
        connected: true,
        profile: null,
      })
    );

    await expect(page.locator('[data-conn-block="steam-connected"]')).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.locator('[data-conn-block="steam-disconnected"]')).toBeHidden({
      timeout: 10_000,
    });
    await expect(page.locator('[data-conn-block="steam-disconnect-form"]')).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.locator('[data-conn-block="steam-connect-link"]')).toBeHidden({
      timeout: 10_000,
    });
  });

  test("steam.profile.changed private profile → steam-private-warning visible", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(
      redisChannel(uuid),
      envelope("steam.profile.changed", {
        profile: {
          hasSteam: true,
          profilePrivate: true,
          offlineMode: false,
          rateLimited: false,
          ttlMinutes: 15,
          hasPersonalToken: false,
          tokenShared: false,
        },
      })
    );

    await expect(page.locator('[data-conn-block="steam-private-warning"]')).toBeVisible({
      timeout: 10_000,
    });
  });
});
