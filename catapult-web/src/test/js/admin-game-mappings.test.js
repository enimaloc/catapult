/*
 * The admin game mappings form: the game field's suggestions follow the platform picked. A lookup still
 * waiting for its debounce, or already sent, when the platform changes is dropped, and the typed text is
 * looked up again on the new platform.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render, response } from "./helpers.js";

const DEBOUNCE_MS = 300;

let fetched;

const platform = () => document.querySelector('[name="sourceType"]');
const sourceId = () => document.querySelector('[name="sourceId"]');
const suggestions = () => Array.from(document.querySelectorAll(".admin-gm-suggestions mdui-list-item"))
    .map(item => item.textContent);

function page() {
    html(`
        <mdui-tab-panel value="game-mappings">
            <div class="admin-view admin-game-mappings" data-page="0" data-missing-page="0"
                 data-label-suggestion="{name} — {id} · {n}" data-label-create-title="New">
                <button id="admin-gm-new"></button>
                <div data-gm-lists></div>
                <div id="admin-gm-dialog">
                    <div class="admin-gm-form">
                        <h3 class="admin-gm-form-title"></h3>
                        <select name="sourceType"><option>STEAM</option><option>XBOX</option></select>
                        <input name="sourceId">
                        <span class="admin-gm-game-name"></span>
                        <div class="admin-gm-suggestions"></div>
                        <input name="category">
                        <div class="admin-gm-category-results"></div>
                        <fieldset data-gm-override="ccls"><input type="checkbox" class="admin-gm-override-switch"><div class="admin-gm-choices"></div></fieldset>
                        <fieldset data-gm-override="tws"><input type="checkbox" class="admin-gm-override-switch"><div class="admin-gm-choices"></div></fieldset>
                        <input type="checkbox" name="applyLive">
                        <p class="admin-gm-error"></p>
                        <p class="admin-gm-result"></p>
                        <button class="admin-gm-save"></button>
                    </div>
                </div>
            </div>
        </mdui-tab-panel>`);
    render();
    document.getElementById("admin-gm-new").click();
}

function type(text) {
    sourceId().value = text;
    sourceId().dispatchEvent(new Event("input"));
}

function pick(value) {
    platform().value = value;
    platform().dispatchEvent(new Event("change"));
}

const game = (sourceId, sourceName) => ({ sourceType: "X", sourceId, sourceName, streamerCount: 1 });

/** A fetch answered only when `resolve` is called. */
function pending() {
    let resolve;
    const promise = new Promise(r => { resolve = r; });
    return { promise, resolve };
}

beforeEach(async () => {
    vi.useFakeTimers();
    fetched = vi.fn(async () => response(200, []));
    window.fetch = fetched;
    window.GameSearch = { attach: () => ({ select: () => {}, selected: () => null }) };
    window.CatapultCsrf = { readCookie: () => "" };
    window.CatapultChannel = { showError: vi.fn() };
    await load("admin-game-mappings");
    page();
});

afterEach(() => {
    vi.useRealTimers();
});

describe("admin-game-mappings suggestions", () => {
    it("looks the game up on the platform picked while the lookup was waiting", async () => {
        type("Halo");
        pick("XBOX");
        await vi.advanceTimersByTimeAsync(DEBOUNCE_MS);

        expect(fetched).toHaveBeenCalledTimes(1);
        expect(fetched).toHaveBeenCalledWith("/admin/game-mappings/detected?sourceType=XBOX&q=Halo");
    });

    it("drops the answer of a lookup sent for the old platform", async () => {
        const steam = pending();
        const xbox = pending();
        fetched.mockReturnValueOnce(steam.promise).mockReturnValueOnce(xbox.promise);

        type("Halo");
        await vi.advanceTimersByTimeAsync(DEBOUNCE_MS);
        pick("XBOX");
        await vi.advanceTimersByTimeAsync(DEBOUNCE_MS);
        expect(fetched).toHaveBeenLastCalledWith("/admin/game-mappings/detected?sourceType=XBOX&q=Halo");

        steam.resolve(response(200, [game("123", "Steam game")]));
        await vi.advanceTimersByTimeAsync(0);
        expect(suggestions()).toEqual([]);

        xbox.resolve(response(200, [game("x1", "Halo")]));
        await vi.advanceTimersByTimeAsync(0);
        expect(suggestions()).toEqual(["Halo — x1 · 1"]);
    });

    it("clears the old platform's suggestions at once", async () => {
        fetched.mockResolvedValueOnce(response(200, [game("123", "Steam game")]));
        type("Halo");
        await vi.advanceTimersByTimeAsync(DEBOUNCE_MS);
        expect(suggestions()).toEqual(["Steam game — 123 · 1"]);

        pick("XBOX");

        expect(suggestions()).toEqual([]);
    });
});
