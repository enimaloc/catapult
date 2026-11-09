import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

// The widget relay page has no OBS-websocket to talk to in CI, so we stub
// obsWsConnect and assert on the calls it makes to the stub's `call()` —
// this exercises the WS-subscribe → relay logic in twitchat-relay.js
// without a real OBS instance.
//
// The stub MUST be injected via page.route() on obs-websocket-client.js
// itself, not page.addInitScript(): the real obs-websocket-client.js does
// `global.obsWsConnect = connect` when it loads, which runs AFTER an
// addInitScript-installed stub and silently clobbers it — the test would
// then try to talk to a real OBS-websocket instance and hang until timeout.

test.describe("twitchat widget relay", () => {
  // Precondition beyond the env vars checked below: the seeded widget token's backing
  // TwitchatWidgetSettings row must also have obsHost/obsPort configured (not just exist) —
  // otherwise twitchat-relay.js never calls obsWsConnect and this test times out waiting
  // for a call that never happens.
  test.skip(
    !process.env.CATAPULT_E2E_TWITCHAT_WIDGET_TOKEN || !process.env.CATAPULT_E2E_TWITCHAT_OWNER_ID,
    "requires a seeded twitchat widget token/owner id; set CATAPULT_E2E_TWITCHAT_WIDGET_TOKEN/CATAPULT_E2E_TWITCHAT_OWNER_ID"
  );

  test("relays a twitchat.notify event to BroadcastCustomEvent", async ({ page }) => {
    const widgetToken = process.env.CATAPULT_E2E_TWITCHAT_WIDGET_TOKEN;
    const ownerId = process.env.CATAPULT_E2E_TWITCHAT_OWNER_ID;

    await page.addInitScript(() => {
      (window as any).__obsCalls = [];
      // twitchat.widget.<token> is a fire-and-forget channel with no missed-event replay
      // (see the event-driven-channel-page design doc) — publishing to Redis before the
      // WS "subscribe" round-trip completes silently drops the message. This page only
      // ever subscribes to one channel, so any ws:sub.ok is the one we're waiting for.
      // Registered via addInitScript so the listener exists before ws-client.js runs.
      (window as any).__subscribed = false;
      document.addEventListener("ws:sub.ok", () => {
        (window as any).__subscribed = true;
      });
    });

    await page.route("**/js/widget/obs-websocket-client.js", (route) =>
      route.fulfill({
        contentType: "text/javascript",
        body: `
          window.obsWsConnect = async () => ({
            call: async (requestType, requestData) => {
              window.__obsCalls.push({ requestType, requestData });
              return {};
            }
          });
        `
      })
    );

    await page.goto(`/widget/twitchat/${widgetToken}`);

    await page.waitForFunction(() => (window as any).__subscribed === true, undefined, {
      timeout: 10_000
    });

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

    await page.waitForFunction(() => (window as any).__obsCalls && (window as any).__obsCalls.length > 0, undefined, {
      timeout: 10_000
    });

    const calls = await page.evaluate(() => (window as any).__obsCalls);
    expect(calls[0].requestType).toBe("BroadcastCustomEvent");
    expect(calls[0].requestData.eventData.type).toBe("CUSTOM_CHAT_MESSAGE");
    expect(calls[0].requestData.eventData.data.message).toBe("Le bot a été activé.");
  });
});
