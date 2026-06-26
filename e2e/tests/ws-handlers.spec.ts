import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

const CHANNEL = process.env.CATAPULT_E2E_CHANNEL_USERNAME || "enimaloc";

declare global {
  interface Window {
    catapultWs: {
      request: (
        action: string,
        params: Record<string, unknown>
      ) => Promise<{ ok: boolean; result: unknown }>;
      subscribe: (channel: string, handler: (frame: unknown) => void) => void;
    };
  }
}

test.describe("WS handlers — direct request()", () => {
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

  test("search.twitch.categories returns ok:true with results", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const channelId = process.env.CATAPULT_E2E_USER_UUID || "";
    const resp = await page.evaluate(
      async (params: { channelId: string; q: string; limit: number }) => {
        return await window.catapultWs.request("search.twitch.categories", params);
      },
      { channelId, q: "sky", limit: 5 }
    );

    expect(resp.ok).toBe(true);
    expect(Array.isArray(resp.result)).toBe(true);
    expect((resp.result as unknown[]).length).toBeGreaterThan(0);
  });

  test("search.dtdd returns ok:true with results shape", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const resp = await page.evaluate(
      async (params: { q: string; limit: number }) => {
        return await window.catapultWs.request("search.dtdd", params);
      },
      { q: "x", limit: 5 }
    );

    expect(resp.ok).toBe(true);
    const result = resp.result as { results: unknown[] };
    expect(result).toHaveProperty("results");
    expect(Array.isArray(result.results)).toBe(true);
  });

  test("notification.list returns ok:true with content array", async ({ page }) => {
    test.setTimeout(60_000);

    await gotoAndWaitWs(page);

    const resp = await page.evaluate(
      async (params: { page: number; size: number }) => {
        return await window.catapultWs.request("notification.list", params);
      },
      { page: 0, size: 5 }
    );

    expect(resp.ok).toBe(true);
    const result = resp.result as { content: unknown[] };
    expect(result).toHaveProperty("content");
    expect(Array.isArray(result.content)).toBe(true);
  });

  test.describe("admin-only handlers", () => {
    test.skip(
      !process.env.CATAPULT_E2E_AUTH_ROLE_ADMIN,
      "admin role required; set CATAPULT_E2E_AUTH_ROLE_ADMIN"
    );

    test("admin.config.catalog returns ok:true with array result", async ({ page }) => {
      test.setTimeout(60_000);

      await gotoAndWaitWs(page);

      const resp = await page.evaluate(
        async (params: { module: string }) => {
          return await window.catapultWs.request("admin.config.catalog", params);
        },
        { module: "api" }
      );

      expect(resp.ok).toBe(true);
      expect(Array.isArray(resp.result)).toBe(true);
    });

    test("admin.broadcast.send returns ok:true and broadcast frame arrives", async ({ page }) => {
      test.setTimeout(60_000);

      await gotoAndWaitWs(page);

      // Subscribe to events.global before sending
      await page.evaluate(() => {
        window.__broadcastFrames = [];
        window.catapultWs.subscribe("events.global", (frame: unknown) => {
          (window as unknown as { __broadcastFrames: unknown[] }).__broadcastFrames.push(frame);
        });
      });

      await page.waitForTimeout(500);

      const resp = await page.evaluate(
        async (params: { channel: string; name: string; data: Record<string, unknown> }) => {
          return await window.catapultWs.request("admin.broadcast.send", params);
        },
        {
          channel: "events.global",
          name: "alert.info",
          data: { title: "E2E test", body: "x", ttlSeconds: 60 },
        }
      );

      expect(resp.ok).toBe(true);
      const result = resp.result as { accepted: boolean };
      expect(result.accepted).toBe(true);

      // Wait for the broadcast frame to arrive via subscription
      await page.waitForFunction(
        () => {
          const w = window as unknown as { __broadcastFrames?: unknown[] };
          return (w.__broadcastFrames?.length ?? 0) > 0;
        },
        undefined,
        { timeout: 10_000 }
      );

      const frames = await page.evaluate(
        () => (window as unknown as { __broadcastFrames: unknown[] }).__broadcastFrames
      );
      expect(frames.length).toBeGreaterThan(0);
    });
  });
});

// Augment window for internal test tracking
declare global {
  interface Window {
    __broadcastFrames: unknown[];
  }
}
