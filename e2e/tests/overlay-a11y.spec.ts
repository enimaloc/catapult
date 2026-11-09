/**
 * Accessibility and interaction tests for the maintenance overlay/banner/toast UI.
 * No backend interaction — all states are driven by JS-dispatched CustomEvents
 * so the suite stays fast and deterministic.
 *
 * Prerequisite: docker compose up -d (catapult-web reachable on http://localhost).
 */

import { test, expect, Page } from "@playwright/test";

const BASE_URL = process.env.CATAPULT_E2E_BASE_URL || "http://localhost";

async function bootPage(page: Page): Promise<void> {
    await page.goto(BASE_URL + "/");
    await page.waitForFunction(() => (window as any).catapultWs !== undefined, { timeout: 10_000 });
}

test("overlay traps focus inside the card", async ({ page }) => {
    await bootPage(page);

    // Trigger overlay via the internal show function (the JS exposes it as window.maintenanceOverlay).
    await page.evaluate(() => {
        document.dispatchEvent(new CustomEvent("ws:degraded"));
    });

    await expect(page.locator("#maintenance-overlay")).not.toHaveAttribute("hidden", /.*/);

    // Title gets focus shortly after show
    await page.waitForTimeout(150);
    const focusedId = await page.evaluate(() => document.activeElement?.id);
    expect(focusedId).toBe("m-title");

    // Tab inside the card should not escape (no focusable button → focus stays on title)
    await page.keyboard.press("Tab");
    const focusedAfter = await page.evaluate(() => document.activeElement?.id);
    expect(focusedAfter).toBe("m-title");

    // Shift+Tab also stays inside
    await page.keyboard.press("Shift+Tab");
    const focusedAfterShift = await page.evaluate(() => document.activeElement?.id);
    expect(focusedAfterShift).toBe("m-title");
});

test("overlay applies inert while visible and removes it on hide", async ({ page }) => {
    await bootPage(page);

    await page.evaluate(() => document.dispatchEvent(new CustomEvent("ws:degraded")));
    await expect(page.locator("#maintenance-overlay")).not.toHaveAttribute("hidden", /.*/);

    // Targets are either <main>(s) or body direct children (excluding overlay
    // chrome). Check at least one element other than the overlay is inert.
    const inertCountWhileVisible = await page.evaluate(() => {
        const excluded = new Set(["maintenance-overlay", "maintenance-banner", "toast-container"]);
        const mains = Array.from(document.querySelectorAll("main"));
        const candidates = mains.length > 0
            ? mains
            : Array.from(document.body.children).filter((el) => !excluded.has(el.id));
        return candidates.filter((el) => el.hasAttribute("inert")).length;
    });
    expect(inertCountWhileVisible).toBeGreaterThan(0);

    // Trigger hide via ws:open coming after a previous open (which the
    // initial bootPage already set wasConnected=true on).
    await page.evaluate(() => document.dispatchEvent(new CustomEvent("ws:open")));
    await expect(page.locator("#maintenance-overlay")).toHaveAttribute("hidden", /.*/);

    const inertCountAfterHide = await page.evaluate(() => {
        const excluded = new Set(["maintenance-overlay", "maintenance-banner", "toast-container"]);
        const mains = Array.from(document.querySelectorAll("main"));
        const candidates = mains.length > 0
            ? mains
            : Array.from(document.body.children).filter((el) => !excluded.has(el.id));
        return candidates.filter((el) => el.hasAttribute("inert")).length;
    });
    expect(inertCountAfterHide).toBe(0);
});

test("overlay restores previous focus when hidden", async ({ page }) => {
    await bootPage(page);

    // Focus a known element in the page (the brand link in template.html)
    await page.evaluate(() => {
        const link = document.querySelector(".nav-brand") as HTMLElement | null;
        link?.focus();
    });
    const beforeId = await page.evaluate(() => document.activeElement?.tagName);
    expect(beforeId).toBe("A");

    await page.evaluate(() => document.dispatchEvent(new CustomEvent("ws:degraded")));
    await expect(page.locator("#maintenance-overlay")).not.toHaveAttribute("hidden", /.*/);

    await page.evaluate(() => document.dispatchEvent(new CustomEvent("ws:open")));
    await expect(page.locator("#maintenance-overlay")).toHaveAttribute("hidden", /.*/);

    // Focus should be restored
    const restored = await page.evaluate(() => document.activeElement?.className);
    expect(restored).toContain("nav-brand");
});

test("overlay sets data-cause=shutdown when maintenance.imminent received", async ({ page }) => {
    await bootPage(page);

    await page.evaluate(() => {
        document.dispatchEvent(new CustomEvent("ws:event", {
            detail: {
                type: "event",
                channel: "events.global",
                name: "maintenance.imminent",
                data: { reason: "shutdown", etaSeconds: 10 },
            },
        }));
    });

    await expect(page.locator("#maintenance-overlay")).not.toHaveAttribute("hidden", /.*/);
    const cause = await page.locator(".overlay-card").getAttribute("data-cause");
    expect(cause).toBe("shutdown");

    // Countdown should appear
    const countdownVisible = await page.locator("#m-countdown").isVisible();
    expect(countdownVisible).toBe(true);
    const countdownText = await page.locator("#m-countdown").textContent();
    expect(countdownText).toMatch(/\d+\s*s/);
});

test("banner appears for scheduled maintenance > 5 min ahead", async ({ page }) => {
    await bootPage(page);

    const startsAt = new Date(Date.now() + 20 * 60 * 1000).toISOString();   // +20 min
    await page.evaluate((startsAt) => {
        document.dispatchEvent(new CustomEvent("ws:event", {
            detail: {
                type: "event",
                channel: "events.global",
                name: "maintenance.scheduled",
                data: { startsAt, durationMinutes: 10, message: "test banner" },
            },
        }));
    }, startsAt);

    await expect(page.locator("#maintenance-banner")).not.toHaveAttribute("hidden", /.*/);
    const msg = await page.locator("#maintenance-banner .banner-msg").textContent();
    expect(msg).toMatch(/Maintenance/);
});

test("banner dismiss button hides banner and persists in sessionStorage", async ({ page }) => {
    await bootPage(page);

    const startsAt = new Date(Date.now() + 25 * 60 * 1000).toISOString();
    await page.evaluate((startsAt) => {
        document.dispatchEvent(new CustomEvent("ws:event", {
            detail: {
                type: "event",
                channel: "events.global",
                name: "maintenance.scheduled",
                data: { startsAt, durationMinutes: 10, message: "test dismiss" },
            },
        }));
    }, startsAt);

    await expect(page.locator("#maintenance-banner")).not.toHaveAttribute("hidden", /.*/);
    // The banner uses fixed positioning above other layers — Playwright's
    // auto-actionability check can disagree with that, so trigger the click
    // event directly on the button.
    await page.evaluate(() => {
        (document.getElementById("b-dismiss") as HTMLButtonElement | null)?.click();
    });
    await expect(page.locator("#maintenance-banner")).toHaveAttribute("hidden", /.*/);

    // Check sessionStorage holds a dismissed marker keyed on startsAt.
    // Storage is not a plain Object so Object.entries(sessionStorage) is
    // empty — iterate the Storage API instead.
    const storedCount = await page.evaluate(() => {
        let count = 0;
        for (let i = 0; i < sessionStorage.length; i++) {
            const k = sessionStorage.key(i);
            if (k && k.includes("maintenance-banner-dismissed")) count++;
        }
        return count;
    });
    expect(storedCount).toBeGreaterThan(0);
});

test("maintenance.cancelled clears the banner", async ({ page }) => {
    await bootPage(page);

    const startsAt = new Date(Date.now() + 25 * 60 * 1000).toISOString();
    await page.evaluate((startsAt) => {
        document.dispatchEvent(new CustomEvent("ws:event", {
            detail: {
                channel: "events.global", name: "maintenance.scheduled",
                data: { startsAt, durationMinutes: 10, message: "to cancel" },
            },
        }));
    }, startsAt);
    await expect(page.locator("#maintenance-banner")).not.toHaveAttribute("hidden", /.*/);

    await page.evaluate((startsAt) => {
        document.dispatchEvent(new CustomEvent("ws:event", {
            detail: {
                channel: "events.global", name: "maintenance.cancelled",
                data: { originalStartsAt: startsAt, reason: "test cancel" },
            },
        }));
    }, startsAt);
    await expect(page.locator("#maintenance-banner")).toHaveAttribute("hidden", /.*/);
});

test("toast auto-dismisses after its duration", async ({ page }) => {
    await bootPage(page);

    // Trigger reconnect-style toast by simulating a degraded → open cycle
    await page.evaluate(() => document.dispatchEvent(new CustomEvent("ws:degraded")));
    await expect(page.locator("#maintenance-overlay")).not.toHaveAttribute("hidden", /.*/);
    await page.evaluate(() => document.dispatchEvent(new CustomEvent("ws:open")));

    const toast = page.locator("#toast-container").locator("text=Connexion rétablie");
    await expect(toast).toBeVisible({ timeout: 3_000 });
    // Default toast duration is 3000ms — wait a bit more
    await page.waitForTimeout(3_500);
    await expect(toast).toHaveCount(0);
});

test("prefers-reduced-motion disables overlay transitions", async ({ browser }) => {
    const context = await browser.newContext({ reducedMotion: "reduce" });
    const page = await context.newPage();
    await page.goto(BASE_URL + "/");
    await page.waitForFunction(() => (window as any).catapultWs !== undefined, { timeout: 10_000 });

    // Show overlay and inspect computed transition
    await page.evaluate(() => document.dispatchEvent(new CustomEvent("ws:degraded")));
    await expect(page.locator("#maintenance-overlay")).not.toHaveAttribute("hidden", /.*/);

    const card = await page.locator(".overlay-card");
    const transitionDuration = await card.evaluate((el) =>
        window.getComputedStyle(el).transitionDuration);
    // With reduced motion, transition should be "0s" (or instant)
    expect(transitionDuration).toMatch(/^0/);

    await context.close();
});

test("WS anonymous: events.global allowed, notifications.user denied", async ({ page }) => {
    await bootPage(page);

    // Wait for catapultWs to have a live socket
    await page.waitForTimeout(1500);

    // Subscribe to events.global → expect sub.ok
    const globalAck = await page.evaluate(() => new Promise<string>((resolve) => {
        const handler = (e: any) => {
            const msg = e.detail;
            if (msg.type === "sub.ok" && msg.channel === "events.global") {
                document.removeEventListener("ws:sub.ok", handler);
                resolve("ok");
            }
        };
        document.addEventListener("ws:sub.ok", handler);
        (window as any).catapultWs.send({ type: "subscribe", channel: "events.global" });
        setTimeout(() => resolve("timeout"), 4_000);
    }));
    expect(globalAck).toBe("ok");

    // Subscribe to notifications.user (anonymous WS) → expect sub.denied
    const userDenied = await page.evaluate(() => new Promise<string>((resolve) => {
        const handler = (e: any) => {
            const msg = e.detail;
            if (msg.type === "sub.denied" && msg.channel === "notifications.user") {
                document.removeEventListener("ws:sub.denied", handler);
                resolve(msg.reason || "denied");
            }
        };
        document.addEventListener("ws:sub.denied", handler);
        (window as any).catapultWs.send({ type: "subscribe", channel: "notifications.user" });
        setTimeout(() => resolve("timeout"), 4_000);
    }));
    expect(userDenied).not.toBe("timeout");
    expect(userDenied.toUpperCase()).toMatch(/FORBIDDEN|UNAUTH/);
});

test("/ws/auth-ticket returns 401 for anonymous user", async ({ page }) => {
    const response = await page.request.get(BASE_URL + "/ws/auth-ticket");
    expect(response.status()).toBe(401);
});

// Note: ws-client.js intentionally swallows ping frames and does not dispatch
// ws:ping (heartbeat is a transport-level concern, not a UI signal). Server
// heartbeat is verified via the standalone websocat scenario in manual-smoke.
