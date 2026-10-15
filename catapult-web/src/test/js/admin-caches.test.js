/*
 * The admin caches tab's entries: the detail button opens the entry's record in a dialog, the
 * delete button evicts it once confirmed and reloads the results as they were.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let fetched;
let posted;
let confirmAnswer;

function page() {
    html(`
        <mdui-tabs id="admin-tabs"></mdui-tabs>
        <mdui-tab-panel value="caches" data-admin-sub="igdb-game-cache">
            <div class="admin-view" data-name="igdb-game-cache" data-confirm-text="OK" data-cancel-text="No" data-error="Failed ({0})">
                <div data-admin-results>
                    <div class="admin-row" data-key="a b">
                        <button class="admin-cache-detail"></button>
                        <button class="admin-cache-delete" data-confirm="Remove a b?"></button>
                    </div>
                </div>
                <div id="admin-cache-dialog"><div class="admin-dialog-body"></div><button class="admin-dialog-close"></button></div>
            </div>
        </mdui-tab-panel>`);
    render();
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

beforeEach(async () => {
    history.replaceState(null, "", "/admin#caches/igdb-game-cache");
    fetched = vi.fn(async () => response(200, "<p class='detail'>Doom</p>"));
    posted = vi.fn(async () => response(204));
    confirmAnswer = true;
    window.fetch = fetched;
    window.CatapultCsrf = { postJson: posted };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())) };
    await load("channel-page", "admin-tabs", "admin-caches");
});

describe("admin-caches", () => {
    it("opens an entry's record in the dialog", async () => {
        page();
        await click(".admin-cache-detail");

        expect(fetched.mock.calls.map(([url]) => url)).toEqual(["/spa/admin/caches/igdb-game-cache/entry?key=a+b"]);
        expect(document.querySelector("#admin-cache-dialog .detail").textContent).toBe("Doom");
        expect(document.getElementById("admin-cache-dialog").open).toBe(true);

        await click(".admin-dialog-close");
        expect(document.getElementById("admin-cache-dialog").open).toBe(false);
    });

    it("says so when the record can't be fetched", async () => {
        fetched.mockResolvedValue(response(502, ""));
        page();
        await click(".admin-cache-detail");

        expect(document.querySelector("mdui-snackbar").textContent).toBe("Failed (502)");
        expect(document.getElementById("admin-cache-dialog").open).not.toBe(true);
    });

    it("evicts a confirmed entry, then reloads the results", async () => {
        page();
        await click(".admin-cache-delete");

        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Remove a b?", confirmText: "OK" }));
        expect(posted.mock.calls).toEqual([["/admin/caches/igdb-game-cache/delete?key=a+b", {}]]);
        expect(fetched.mock.calls.map(([url]) => url)).toEqual(["/spa/admin/caches/igdb-game-cache?q=&page=0"]);
    });

    it("leaves the entry alone when the admin cancels", async () => {
        confirmAnswer = false;
        page();
        await click(".admin-cache-delete");

        expect(posted).not.toHaveBeenCalled();
    });
});
