import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

// The widget relay page has no OBS-websocket to talk to in CI, so we stub
// obsWsConnect before the page's own script runs and assert on the calls
// it makes to the stub's `call()` — this exercises the WS-subscribe →
// relay logic in twitchat-relay.js without a real OBS instance.

test.describe("twitchat widget relay", () => {
  test.skip(
    !process.env.CATAPULT_E2E_TWITCHAT_WIDGET_TOKEN || !process.env.CATAPULT_E2E_TWITCHAT_OWNER_ID,
    "requires a seeded twitchat widget token/owner id; set CATAPULT_E2E_TWITCHAT_WIDGET_TOKEN/CATAPULT_E2E_TWITCHAT_OWNER_ID"
  );

  test("relays a twitchat.notify event to BroadcastCustomEvent", async ({ page }) => {
    const widgetToken = process.env.CATAPULT_E2E_TWITCHAT_WIDGET_TOKEN;
    const ownerId = process.env.CATAPULT_E2E_TWITCHAT_OWNER_ID;

    await page.addInitScript(() => {
      (window as any).__obsCalls = [];
      (window as any).obsWsConnect = async () => ({
        call: async (requestType: string, requestData: unknown) => {
          (window as any).__obsCalls.push({ requestType, requestData });
          return {};
        }
      });
    });

    await page.goto(`/widget/twitchat/${widgetToken}`);

    publishRedis(
      `catapult:events:twitchat:${ownerId}`,
      JSON.stringify({
        name: "twitchat.notify",
        data: {
          message: "Le bot a été activé.",
          style: "message",
          actions: [{ label: "Désactiver le bot", actionType: "url", url: "https://x/y", theme: "alert" }]
        },
        ts: Date.now()
      })
    );

    await page.waitForFunction(() => (window as any).__obsCalls && (window as any).__obsCalls.length > 0);

    const calls = await page.evaluate(() => (window as any).__obsCalls);
    expect(calls[0].requestType).toBe("BroadcastCustomEvent");
    expect(calls[0].requestData.eventData.type).toBe("CUSTOM_CHAT_MESSAGE");
    expect(calls[0].requestData.eventData.data.message).toBe("Le bot a été activé.");
  });
});
