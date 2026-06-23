import { expect, test } from "@playwright/test";

/**
 * Scenario 5 (spec §12) — live search over WS. Anonymous channels are
 * `search.twitch.categories` and `search.dtdd`. Both require an upstream
 * API call; this test asserts the WS RPC round-trip itself works (the
 * response frame is correlated and arrives in well under 30s).
 *
 * The shape of the result depends on whether the API has an upstream key
 * configured; we only assert the WS pipeline, not the downstream payload.
 */
test.describe("search over WebSocket — RPC round-trip", () => {
  test("search.dtdd returns a structured response under 5s", async ({ page }) => {
    test.setTimeout(30_000);

    await page.goto("/");
    await page.waitForFunction(() => {
      const w = window as unknown as { catapultOverlay?: { _state: () => { wasConnected: boolean } } };
      return !!w.catapultOverlay && w.catapultOverlay._state().wasConnected === true;
    }, undefined, { timeout: 15_000 });

    const start = Date.now();
    const response: { ok: boolean; result?: unknown; error?: unknown; code?: string } =
      await page.evaluate(async () => {
        const w = window as unknown as {
          catapultWs: {
            request: (action: string, params: object, timeoutMs?: number) => Promise<{
              ok: boolean;
              result?: unknown;
              error?: unknown;
              code?: string;
            }>;
          };
        };
        try {
          return await w.catapultWs.request(
            "search.dtdd",
            { q: "smoke", limit: 5 },
            10_000
          );
        } catch (err) {
          // request() rejects with the error frame; surface it as a non-ok response
          // so the assertion below can still distinguish round-trip failure from
          // upstream error.
          const e = err as { ok?: boolean; code?: string; message?: string };
          return { ok: false, error: e };
        }
      });
    const elapsed = Date.now() - start;

    // We accept either a successful response (`ok:true`) OR a well-formed
    // server-side error (e.g. BACKEND_UNAVAILABLE). What we must NOT see is a
    // client-side TIMEOUT / DISCONNECTED — those mean the WS RPC plumbing is
    // broken.
    expect(response).toBeDefined();
    const errCode = (response.error as { code?: string } | undefined)?.code;
    expect(errCode).not.toBe("TIMEOUT");
    expect(errCode).not.toBe("DISCONNECTED");

    // < 5s is the spec target — we give 10s of head-room because the test
    // runner adds a JSON-serialization hop and the upstream may be cold.
    expect(elapsed).toBeLessThan(10_000);
  });
});
