/*
 * The Battle.net variants section of the admin game mappings tab: a row's patterns reach the form as typed, and
 * are sent back as typed (a trailing space kept, only empty lines dropped); a refusal is told in the form.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render, response } from "./helpers.js";

let fetched;

const field = name => document.querySelector(`#admin-bv-dialog [name="${name}"]`);

function page() {
    html(`
        <mdui-tab-panel value="game-mappings">
            <div class="admin-view admin-game-mappings" data-page="0" data-missing-page="0">
                <section class="admin-bnet-variants" data-label-create-title="New" data-label-edit-title="Edit"
                         data-label-saved="Saved" data-label-error-409="Key used" data-label-unavailable="Unavailable">
                    <button id="admin-bv-new"></button>
                    <div data-bv-list>
                        <div class="admin-bv-row" data-id="v1" data-fourcc="WoW" data-variant-key="Forever"
                             data-match-type="PREFIX" data-display-name="World of Warcraft: Forever">
                            <span class="admin-bv-pattern">Forever - </span>
                            <span class="admin-bv-pattern">Für immer - </span>
                            <button data-bv-edit="v1"></button>
                        </div>
                    </div>
                </section>
                <div id="admin-bv-dialog">
                    <div class="admin-gm-form">
                        <h3 class="admin-bv-form-title"></h3>
                        <input name="fourcc"><input name="variantKey">
                        <p class="admin-bv-source-id"></p>
                        <select name="matchType"><option>PREFIX</option><option>CONTAINS</option></select>
                        <textarea name="patterns"></textarea>
                        <input name="displayName">
                        <input type="checkbox" name="enabled">
                        <p class="admin-gm-error" hidden></p>
                        <button class="admin-bv-save"></button>
                    </div>
                </div>
            </div>
        </mdui-tab-panel>`);
    render();
}

const sentBody = () => JSON.parse(fetched.mock.calls.find(call => call[1]?.method)[1].body);

beforeEach(async () => {
    fetched = vi.fn(async () => response(200, {}));
    window.fetch = fetched;
    window.CatapultCsrf = { readCookie: () => "" };
    await load("admin-battlenet-variants");
    window.CatapultChannel = { showError: vi.fn() };
    page();
});

describe("patternsOf", () => {
    it("keeps each line as typed but for a pasted \\r, and drops empty lines", () => {
        expect(window.CatapultAdminBattleNetVariants.patternsOf("Forever - \r\n\nFür immer - ")).toEqual(
            ["Forever - ", "Für immer - "]);
    });
});

describe("the form", () => {
    it("opens on a row with its patterns as typed and the ID to map", () => {
        document.querySelector("[data-bv-edit]").click();

        expect(field("patterns").value).toBe("Forever - \nFür immer - ");
        expect(document.querySelector(".admin-bv-source-id").textContent).toBe("WoW:Forever");
        expect(document.querySelector(".admin-bv-form-title").textContent).toBe("Edit");
    });

    it("sends the row's patterns back untrimmed", async () => {
        document.querySelector("[data-bv-edit]").click();
        field("enabled").checked = true;

        document.querySelector(".admin-bv-save").click();
        await vi.waitFor(() => expect(fetched).toHaveBeenCalled());

        const [url, init] = fetched.mock.calls[0];
        expect(url).toBe("/admin/battlenet-game-variants/v1");
        expect(init.method).toBe("PUT");
        expect(sentBody()).toEqual({ fourcc: "WoW", variantKey: "Forever", matchType: "PREFIX",
            patterns: ["Forever - ", "Für immer - "], displayName: "World of Warcraft: Forever", enabled: true });
    });

    it("tells a refusal in the form", async () => {
        fetched.mockImplementation(async () => response(409, null));
        document.getElementById("admin-bv-new").click();
        field("fourcc").value = "WoW";
        field("variantKey").value = "Forever";
        field("patterns").value = "Forever - ";
        field("displayName").value = "Forever";

        document.querySelector(".admin-bv-save").click();

        await vi.waitFor(() => expect(document.querySelector(".admin-gm-error").hidden).toBe(false));
        expect(document.querySelector(".admin-gm-error").textContent).toBe("Key used");
        expect(fetched.mock.calls[0][1].method).toBe("POST");
    });
});
