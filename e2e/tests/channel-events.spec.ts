import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

const CHANNEL = process.env.CATAPULT_E2E_CHANNEL_USERNAME || "enimaloc";
const BINDING_ID = "ffffffff-0000-0000-0000-000000000001";

function redisChannel(uuid: string): string {
  return `catapult:events:channel:${uuid}`;
}

function envelope(name: string, data: Record<string, unknown>): string {
  return JSON.stringify({ name, data, ts: Date.now() });
}

test.describe("channel page — Redis event injection", () => {
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

  test("bot.toggled {enabled:false} → bot-status-inactive visible, bot-status-active hidden", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(redisChannel(uuid), envelope("bot.toggled", { enabled: false }));

    await expect(page.locator("#bot-status-inactive")).toBeVisible({ timeout: 10_000 });
    await expect(page.locator("#bot-status-active")).toBeHidden({ timeout: 10_000 });
  });

  test("bot.toggled {enabled:true} → bot-status-active visible, bot-status-inactive hidden", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(redisChannel(uuid), envelope("bot.toggled", { enabled: true }));

    await expect(page.locator("#bot-status-active")).toBeVisible({ timeout: 10_000 });
    await expect(page.locator("#bot-status-inactive")).toBeHidden({ timeout: 10_000 });
  });

  test("stream.state.changed {isLive:true} → stream-status-live visible, offline hidden", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(redisChannel(uuid), envelope("stream.state.changed", { isLive: true }));

    await expect(page.locator("#stream-status-live")).toBeVisible({ timeout: 10_000 });
    await expect(page.locator("#stream-status-offline")).toBeHidden({ timeout: 10_000 });
  });

  test("stream.state.changed {isLive:false} → stream-status-offline visible, live hidden", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(redisChannel(uuid), envelope("stream.state.changed", { isLive: false }));

    await expect(page.locator("#stream-status-offline")).toBeVisible({ timeout: 10_000 });
    await expect(page.locator("#stream-status-live")).toBeHidden({ timeout: 10_000 });
  });

  test("game.detected → #current-game-name shows sourceName, #current-game-info visible", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(
      redisChannel(uuid),
      envelope("game.detected", { sourceName: "Test", sourceType: "STEAM" })
    );

    await expect(page.locator("#current-game-info")).toBeVisible({ timeout: 10_000 });
    await expect(page.locator("#current-game-name")).toHaveText("Test", { timeout: 10_000 });
  });

  test("game.cleared → #current-game-info hidden, #current-game-none visible", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(redisChannel(uuid), envelope("game.cleared", {}));

    await expect(page.locator("#current-game-info")).toBeHidden({ timeout: 10_000 });
    await expect(page.locator("#current-game-none")).toBeVisible({ timeout: 10_000 });
  });

  test("binding.deleted with absent id → silent no-op, no console error", async ({ page }) => {
    test.setTimeout(60_000);

    const errors: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") errors.push(msg.text());
    });

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(
      redisChannel(uuid),
      envelope("binding.deleted", { bindingId: BINDING_ID })
    );

    await page.waitForTimeout(1_000);
    expect(errors.filter((e) => e.toLowerCase().includes("binding"))).toHaveLength(0);
  });

  test("dtdd.mapping.changed → silent no-op, no console error", async ({ page }) => {
    test.setTimeout(60_000);

    const errors: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") errors.push(msg.text());
    });

    await gotoAndWaitWs(page);

    const uuid = process.env.CATAPULT_E2E_USER_UUID!;
    publishRedis(redisChannel(uuid), envelope("dtdd.mapping.changed", {}));

    await page.waitForTimeout(1_000);
    expect(errors).toHaveLength(0);
  });
});
