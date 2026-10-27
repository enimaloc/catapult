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

/* The lists, the form's save and the deletion dialog. */

const LABELS = `data-label-edit-title="Edit" data-label-create-title="New" data-label-error-id="Need an ID"
    data-label-error-category="Need a category" data-label-unavailable="Unavailable" data-label-error-409="Already mapped"
    data-label-result="B {bindings} L {live} F {failed}" data-label-deleted="Deleted." data-label-delete-title="Delete {name}?"`;

const ROW = `<div class="admin-gm-row" data-id="7" data-source-type="XBOX" data-source-id="x1" data-source-name="Halo"
    data-twitch-game-id="55" data-twitch-game-name="Halo TW" data-ccls-override data-ccls="a,b" data-tws="">
    <button data-gm-edit></button><button data-gm-delete></button></div>`;

function fullPage(rows = ROW) {
    html(`
        <mdui-tab-panel value="game-mappings">
            <div class="admin-view admin-game-mappings" data-page="1" data-missing-page="0" ${LABELS}>
                <button id="admin-gm-new"></button>
                <div data-gm-lists>${rows}
                    <button id="next" data-gm-page="2"></button><button id="mnext" data-gm-missing-page="3"></button>
                    <button id="create" data-gm-create data-source-type="STEAM" data-source-id="s9" data-source-name="Portal"></button>
                </div>
                <div id="admin-gm-dialog">
                    <div class="admin-gm-form">
                        <h3 class="admin-gm-form-title"></h3>
                        <select name="sourceType"><option>STEAM</option><option>XBOX</option><option>MINECRAFT</option></select>
                        <input name="sourceId"><span class="admin-gm-game-name"></span>
                        <div class="admin-gm-suggestions"></div>
                        <input name="category"><div class="admin-gm-category-results"></div>
                        <fieldset data-gm-override="ccls"><input type="checkbox" class="admin-gm-override-switch">
                            <div class="admin-gm-choices"><mdui-checkbox value="a"></mdui-checkbox><mdui-checkbox value="c"></mdui-checkbox></div></fieldset>
                        <fieldset data-gm-override="tws"><input type="checkbox" class="admin-gm-override-switch">
                            <div class="admin-gm-choices"><mdui-checkbox value="t"></mdui-checkbox></div></fieldset>
                        <input type="checkbox" name="applyLive">
                        <p class="admin-gm-error" hidden></p><p class="admin-gm-result" hidden></p>
                        <button class="admin-gm-save"></button>
                        <button class="admin-gm-close"></button>
                    </div>
                </div>
                <mdui-dialog id="admin-gm-delete-dialog">
                    <span class="admin-gm-delete-title"></span><input type="checkbox" name="applyLive">
                    <button class="admin-gm-confirm-delete"></button>
                </mdui-dialog>
            </div>
        </mdui-tab-panel>`);
    // mdui's checkbox publishes value as a property; a plain element only has the attribute
    document.querySelectorAll("mdui-checkbox").forEach(box => { box.value = box.getAttribute("value"); });
    render();
}

const q = selector => document.querySelector(selector);
const click = selector => q(selector).click();
const error = () => q(".admin-gm-error");
const result = () => q(".admin-gm-result");
const fragment = rows => `<div class="admin-game-mappings" data-page="1" data-missing-page="0"><div data-gm-lists>${rows}</div></div>`;
let selected;

describe("admin-game-mappings form and lists", () => {
    beforeEach(() => {
        selected = null;
        window.GameSearch = { attach: () => ({ select: s => { selected = s; }, selected: () => selected }) };
        window.mdui.snackbar = vi.fn();
    });

    it("opens the edit form from a row with its overrides", () => {
        fullPage();
        click("[data-gm-edit]");

        expect(q(".admin-gm-form-title").textContent).toBe("Edit");
        expect(platform().value).toBe("XBOX");
        expect(sourceId().value).toBe("x1");
        expect(q(".admin-gm-game-name").textContent).toBe("Halo");
        expect(selected).toEqual({ id: "55", name: "Halo TW" });
        expect(q('[data-gm-override="ccls"] .admin-gm-override-switch').checked).toBe(true);
        const [a, c] = document.querySelectorAll('[data-gm-override="ccls"] mdui-checkbox');
        expect([a.checked, c.checked]).toEqual([true, false]);
        expect(q('[data-gm-override="tws"] .admin-gm-override-switch').checked).toBe(false);
        expect(q('[data-gm-override="tws"] .admin-gm-choices').hidden).toBe(true);
        expect(q("#admin-gm-dialog").open).toBe(true);
    });

    it("opens the create form from a missing game's row and from the new button", () => {
        fullPage();
        click("#create");
        expect(platform().value).toBe("STEAM");
        expect(sourceId().value).toBe("s9");
        expect(q(".admin-gm-form-title").textContent).toBe("New");
        expect(selected).toBeNull();

        click("#admin-gm-new");
        expect(sourceId().value).toBe("");
    });

    it("toggles an override's choices with its switch and fills MINECRAFT's ID", () => {
        fullPage();
        click("#admin-gm-new");
        const sw = q('[data-gm-override="tws"] .admin-gm-override-switch');
        sw.checked = true;
        sw.dispatchEvent(new Event("change"));
        expect(q('[data-gm-override="tws"] .admin-gm-choices').hidden).toBe(false);

        pick("MINECRAFT");
        expect(sourceId().value).toBe("minecraft");
    });

    it("closes a dialog with its close button", () => {
        fullPage();
        const dialog = document.createElement("mdui-dialog");
        dialog.open = true;
        q(".admin-gm-close").before(dialog);
        dialog.append(q(".admin-gm-close"));
        q("[data-gm-lists]").append(dialog);
        dialog.querySelector(".admin-gm-close").click();
        expect(dialog.open).toBe(false);
    });

    it("refuses to save without an ID or a category", async () => {
        fullPage();
        click("#admin-gm-new");
        click(".admin-gm-save");
        expect(error().textContent).toBe("Need an ID");
        expect(error().hidden).toBe(false);

        sourceId().value = "  s1 ";
        click(".admin-gm-save");
        expect(error().textContent).toBe("Need a category");
        expect(fetched).not.toHaveBeenCalled();
    });

    it("creates a mapping, tells the outcome and turns into the saved mapping's edit form", async () => {
        fullPage();
        click("#admin-gm-new");
        sourceId().value = " s1 ";
        selected = { id: "9", name: "Portal TW" };
        q('[data-gm-override="ccls"] .admin-gm-override-switch').checked = true;
        q('[data-gm-override="ccls"] mdui-checkbox').checked = true;
        fetched
            .mockResolvedValueOnce(response(200, { mapping: { id: "88" }, bindingsUpdated: 2, liveChannelsUpdated: 1 }))
            .mockResolvedValueOnce(response(200, fragment(ROW)));

        click(".admin-gm-save");
        await vi.advanceTimersByTimeAsync(0);

        const [url, init] = fetched.mock.calls[0];
        expect([url, init.method]).toEqual(["/admin/game-mappings", "POST"]);
        expect(JSON.parse(init.body)).toEqual({
            sourceType: "STEAM", sourceId: "s1", twitchGameId: "9", twitchGameName: "Portal TW",
            ccls: ["a"], tws: null, applyLive: false
        });
        expect(result().textContent).toBe("B 2 L 1 F 0");
        expect(q(".admin-gm-form-title").textContent).toBe("Edit");
        expect(fetched.mock.calls[1][0]).toBe("/spa/admin/game-mappings?page=1&missingPage=0");
        expect(q(".admin-gm-save").disabled).toBe(false);

        selected = { id: "9", name: "Portal TW" };
        fetched.mockResolvedValueOnce(response(200, {})).mockResolvedValueOnce(response(200, fragment(ROW)));
        click(".admin-gm-save");
        await vi.advanceTimersByTimeAsync(0);
        expect(fetched.mock.calls[2][0]).toBe("/admin/game-mappings/88");
        expect(fetched.mock.calls[2][1].method).toBe("PUT");
    });

    it("keeps the override values that have no box on a save", async () => {
        fullPage();
        click("[data-gm-edit]");
        fetched.mockResolvedValue(response(200, {}));
        click(".admin-gm-save");
        await vi.advanceTimersByTimeAsync(0);
        expect(JSON.parse(fetched.mock.calls[0][1].body).ccls).toEqual(["a", "b"]);
        expect(fetched.mock.calls[0][0]).toBe("/admin/game-mappings/7");
    });

    it("tells a refusal by its status and reloads on a 404", async () => {
        fullPage();
        click("#admin-gm-new");
        sourceId().value = "s1";
        selected = { id: "9", name: "n" };
        fetched.mockResolvedValueOnce(response(409, "")).mockResolvedValueOnce(response(500, ""))
            .mockResolvedValueOnce(response(404, "")).mockResolvedValueOnce(response(200, fragment(ROW)));

        click(".admin-gm-save");
        await vi.advanceTimersByTimeAsync(0);
        expect(error().textContent).toBe("Already mapped");
        click(".admin-gm-save");
        await vi.advanceTimersByTimeAsync(0);
        expect(error().textContent).toBe("Unavailable");
        click(".admin-gm-save");
        await vi.advanceTimersByTimeAsync(0);
        expect(fetched).toHaveBeenCalledTimes(4);
        expect(fetched.mock.calls[3][0]).toContain("/spa/admin/game-mappings");
    });

    it("tells unavailable when the request cannot be sent", async () => {
        fullPage();
        click("#admin-gm-new");
        sourceId().value = "s1";
        selected = { id: "9", name: "n" };
        fetched.mockRejectedValueOnce(new Error("offline"));
        click(".admin-gm-save");
        await vi.advanceTimersByTimeAsync(0);
        expect(error().textContent).toBe("Unavailable");
    });

    it("pages the lists, swapping only the lists", async () => {
        fullPage();
        fetched.mockResolvedValue(response(200,
            `<div class="admin-game-mappings" data-page="2" data-missing-page="0"><div data-gm-lists><i id="fresh"></i></div></div>`));
        click("#next");
        await vi.advanceTimersByTimeAsync(0);

        expect(fetched).toHaveBeenCalledWith("/spa/admin/game-mappings?page=2&missingPage=0");
        expect(q("#fresh")).not.toBeNull();
        expect(q(".admin-game-mappings").dataset.page).toBe("2");
        expect(q("#admin-gm-dialog")).not.toBeNull();

    });

    it("pages the missing games list, keeping the shown page of the other", async () => {
        fullPage();
        fetched.mockResolvedValue(response(200, fragment("<i id='m'></i>")));
        click("#mnext");
        await vi.advanceTimersByTimeAsync(0);
        expect(fetched).toHaveBeenCalledWith("/spa/admin/game-mappings?page=1&missingPage=3");
        expect(q("#m")).not.toBeNull();
    });

    it("steps back when a page was emptied, and tells when the lists cannot be fetched", async () => {
        fullPage();
        click("#admin-gm-new");
        fetched
            .mockResolvedValueOnce(response(200, fragment("")))
            .mockResolvedValueOnce(response(200, fragment(ROW)));
        window.CatapultAdminGameMappings.attach();
        q("[data-gm-lists]").insertAdjacentHTML("beforeend", '<button id="p1" data-gm-page="1"></button>');
        click("#p1");
        await vi.advanceTimersByTimeAsync(0);
        expect(fetched.mock.calls.map(c => c[0])).toEqual([
            "/spa/admin/game-mappings?page=1&missingPage=0", "/spa/admin/game-mappings?page=0&missingPage=0"]);

        fetched.mockResolvedValueOnce(response(500, ""));
        q("[data-gm-lists]").insertAdjacentHTML("beforeend", '<button id="p5" data-gm-page="5"></button>');
        click("#p5");
        await vi.advanceTimersByTimeAsync(0);
        expect(window.CatapultChannel.showError).toHaveBeenCalledWith("Unavailable");
    });

    it("asks before deleting, then deletes and reloads", async () => {
        fullPage();
        click("[data-gm-delete]");
        const dialog = q("#admin-gm-delete-dialog");
        expect(dialog.open).toBe(true);
        expect(q(".admin-gm-delete-title").textContent).toBe("Delete Halo?");
        expect(dialog.dataset.id).toBe("7");

        dialog.querySelector('[name="applyLive"]').checked = true;
        fetched.mockResolvedValueOnce(response(200, { bindingsUpdated: 1 })).mockResolvedValueOnce(response(200, fragment(ROW)));
        click(".admin-gm-confirm-delete");
        await vi.advanceTimersByTimeAsync(0);

        expect(fetched.mock.calls[0][0]).toBe("/admin/game-mappings/7?applyLive=true");
        expect(fetched.mock.calls[0][1].method).toBe("DELETE");
        expect(dialog.open).toBe(false);
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Deleted. B 1 L 0 F 0" });
        expect(fetched).toHaveBeenCalledTimes(2);
    });

    it("keeps the deletion dialog on a refusal, and closes it and reloads when the mapping is gone", async () => {
        fullPage();
        click("[data-gm-delete]");
        const dialog = q("#admin-gm-delete-dialog");
        fetched.mockResolvedValueOnce(response(409, "")).mockResolvedValueOnce(response(404, ""))
            .mockResolvedValueOnce(response(200, fragment(ROW)));

        click(".admin-gm-confirm-delete");
        await vi.advanceTimersByTimeAsync(0);
        expect(window.CatapultChannel.showError).toHaveBeenLastCalledWith("Already mapped");
        expect(dialog.open).toBe(true);

        click(".admin-gm-confirm-delete");
        await vi.advanceTimersByTimeAsync(0);
        expect(window.CatapultChannel.showError).toHaveBeenLastCalledWith("Unavailable");
        expect(dialog.open).toBe(false);
        expect(fetched).toHaveBeenCalledTimes(3);
    });

    it("fills a template, leaving unknown keys as they are", () => {
        expect(window.CatapultAdminGameMappings.fill("{a}-{b}", { a: 1 })).toBe("1-{b}");
        expect(window.CatapultAdminGameMappings.fill(undefined, {})).toBe("");
    });
});
