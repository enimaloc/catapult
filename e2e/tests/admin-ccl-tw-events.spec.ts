import { test, expect } from "@playwright/test";
import { publishRedis } from "./helpers/compose";

// CCL and TW admin pages update in place from the `events.admin` channel
// instead of the old full-page POST → redirect reload.

const ADMIN_CHANNEL = "catapult:events:admin";

function envelope(name: string, data: Record<string, unknown>): string {
  return JSON.stringify({ name, data, ts: Date.now() });
}

test.describe("admin ccl/tw — live events", () => {
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

  async function gotoAndWaitWs(page: import("@playwright/test").Page, path: string): Promise<void> {
    await page.goto(path);
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

  test("tw.definition.added appends a row without HTTP reload", async ({ page }) => {
    test.setTimeout(60_000);
    await gotoAndWaitWs(page, "/admin/tw");

    const navigations: string[] = [];
    page.on("framenavigated", (f) => navigations.push(f.url()));

    const id = "e2e_tw_" + Date.now();
    publishRedis(
      ADMIN_CHANNEL,
      envelope("tw.definition.added", {
        definition: { id, label: "E2E label", enabled: true, sortOrder: 3 },
      })
    );

    const row = page.locator(`tr[data-tw-id="${id}"]`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toContainText("E2E label");
    expect(navigations).toHaveLength(0);
  });

  test("ccl.refreshed rebuilds the table from empty", async ({ page }) => {
    test.setTimeout(60_000);
    await gotoAndWaitWs(page, "/admin/ccl");

    const cclId = "E2ELabel" + Date.now();
    publishRedis(
      ADMIN_CHANNEL,
      envelope("ccl.refreshed", {
        ccls: [
          { id: cclId, name: "E2E CCL", description: "desc", mappedDescriptions: ["Violence"] },
        ],
        igdbDescriptors: [
          { id: 1, description: "Violence" },
          { id: 2, description: "Gambling" },
        ],
      })
    );

    const row = page.locator(`tr[data-ccl-id="${cclId}"]`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row.locator(".badge-ccl")).toHaveText(["Violence"]);
  });
});
