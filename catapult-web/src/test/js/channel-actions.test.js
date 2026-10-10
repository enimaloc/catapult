/*
 * The per-tab action scripts: each wires controls to POSTs on catapult:render. Every test renders
 * the relevant markup, fires the control and checks the request(s) sent through CatapultCsrf and
 * whether the page re-renders (CatapultSpa.navigate) afterwards.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;

const posts = () => posted.mock.calls.map(([url, body]) => [url, body]);
const refreshed = () => CatapultSpa.navigate.mock.calls.length;

function toggle(el, checked) {
    el.checked = checked;
    el.dispatchEvent(new Event("change"));
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

beforeEach(async () => {
    history.replaceState(null, "", "/channel/enimaloc");
    posted = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson: posted };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    vi.stubGlobal("fetch", vi.fn(async () => response(200, [])));
    await load("channel-page", "game-search", "channel-status", "channel-bindings",
        "channel-connections", "channel-settings", "channel-dtdd");
});

describe("channel-status", () => {
    it("toggles the bot through its data-toggle-url", async () => {
        html(`<input type="checkbox" id="channel-bot-toggle" data-toggle-url="/channel/enimaloc/settings/bot">`);
        render();

        toggle(document.getElementById("channel-bot-toggle"), false);
        await flush();

        expect(posts()).toEqual([["/channel/enimaloc/settings/bot", {}]]);
        expect(refreshed()).toBe(0);
    });

    it("rechecks the game, then re-renders", async () => {
        html(`<button id="channel-recheck-game" data-recheck-url="/channel/enimaloc/game/recheck"></button>`);
        render();

        await click("#channel-recheck-game");

        expect(posts()).toEqual([["/channel/enimaloc/game/recheck", {}]]);
        expect(refreshed()).toBe(1);
    });

    it("tolerates viewers without these owner-only controls", () => {
        html("");
        expect(() => render()).not.toThrow();
    });
});

describe("channel-bindings", () => {
    beforeEach(() => {
        html(`
            <ul id="channel-bindings-list">
              <mdui-collapse>
                <mdui-collapse-item data-binding-id="b1">
                  <mdui-list-item>
                    <mdui-switch class="binding-ignored-toggle" data-post="ignored-toggle" data-key="ignored"></mdui-switch>
                    <mdui-button-icon class="binding-delete-btn" data-post="delete"></mdui-button-icon>
                    <div class="binding-edit-panel">
                      <input class="binding-edit-game-input" value="Old">
                      <input type="hidden" class="binding-edit-game-id" value="10">
                      <ul class="binding-edit-game-results"></ul>
                    </div>
                    <div class="binding-ccl-panel">
                      <mdui-switch class="binding-ccl-enabled" data-post="ccl-toggle" data-key="enabled"></mdui-switch>
                      <input type="checkbox" class="binding-edit-ccl-checkbox" value="Drugs">
                      <input type="checkbox" class="binding-edit-ccl-checkbox" value="Gore" checked>
                    </div>
                    <div class="binding-tw-panel">
                      <input type="checkbox" class="binding-tw-enabled-checkbox">
                      <input type="checkbox" class="binding-tw-checkbox" value="spiders" checked>
                      <input type="checkbox" class="binding-tw-checkbox" value="flashing">
                      <button class="binding-tw-reset-btn"></button>
                    </div>
                  </mdui-list-item>
                </mdui-collapse-item>
              </mdui-collapse>
            </ul>`);
        render();
    });

    const row = selector => document.querySelector(`[data-binding-id=b1] ${selector}`);

    it("posts generic boolean switches to their data-post endpoint", async () => {
        toggle(row(".binding-ignored-toggle"), true);
        toggle(row(".binding-ccl-enabled"), false);
        await flush();

        expect(posts()).toEqual([
            ["/channel/enimaloc/bindings/b1/ignored-toggle", { ignored: true }],
            ["/channel/enimaloc/bindings/b1/ccl-toggle", { enabled: false }],
        ]);
    });

    it("posts icon buttons to their data-post endpoint", async () => {
        await click("[data-binding-id=b1] .binding-delete-btn");

        expect(posts()).toEqual([["/channel/enimaloc/bindings/b1/delete", {}]]);
    });

    it("resends game and every checked ccl when a ccl switch changes", async () => {
        toggle(row(".binding-edit-ccl-checkbox[value=Drugs]"), true);
        await flush();

        expect(posts()).toEqual([["/channel/enimaloc/bindings/b1",
            { twitchGameId: "10", twitchGameName: "Old", ccls: ["Drugs", "Gore"] }]]);
    });

    it("saves the picked game together with the current ccls", async () => {
        vi.useFakeTimers();
        fetch.mockResolvedValueOnce(response(200, [{ id: "42", name: "Doom" }]));
        const input = row(".binding-edit-game-input");
        input.value = "doom";
        input.dispatchEvent(new Event("input"));
        await vi.runAllTimersAsync();

        row(".binding-edit-game-results").firstElementChild.click();
        await vi.runAllTimersAsync();

        expect(fetch).toHaveBeenCalledWith("/channel/enimaloc/games/search?q=doom");
        expect(row(".binding-edit-game-id").value).toBe("42");
        expect(posts()).toEqual([["/channel/enimaloc/bindings/b1",
            { twitchGameId: "42", twitchGameName: "Doom", ccls: ["Gore"] }]]);
    });

    it("sends nulls for an empty game", async () => {
        row(".binding-edit-game-input").value = "";
        row(".binding-edit-game-id").value = "";
        toggle(row(".binding-edit-ccl-checkbox[value=Gore]"), false);
        await flush();

        expect(posts()).toEqual([["/channel/enimaloc/bindings/b1", { twitchGameId: null, twitchGameName: null, ccls: [] }]]);
    });

    it("posts trigger-warning changes to the binding-scoped endpoints", async () => {
        toggle(row(".binding-tw-enabled-checkbox"), true);
        toggle(row(".binding-tw-checkbox[value=flashing]"), true);
        await click("[data-binding-id=b1] .binding-tw-reset-btn");

        expect(posts()).toEqual([
            ["/channel/bindings/b1/tw-enabled", { enabled: true }],
            ["/channel/bindings/b1/tws", { tws: ["spiders", "flashing"] }],
            ["/channel/bindings/b1/tws/reset", {}],
        ]);
    });

    it("mirrors the accordion's open state as .is-open", () => {
        const item = document.querySelector("mdui-collapse-item");

        item.dispatchEvent(new Event("open"));
        expect(item.classList.contains("is-open")).toBe(true);

        item.dispatchEvent(new Event("close"));
        expect(item.classList.contains("is-open")).toBe(false);
    });
});

describe("channel-connections", () => {
    beforeEach(() => {
        html(`
            <div id="channel-connections">
              <input type="checkbox" id="steam-token-shared">
              <button id="steam-token-delete-btn"></button>
              <input id="steam-token-input" value="KEY">
              <input type="checkbox" id="steam-token-share-new" checked>
              <button id="steam-token-save-btn"></button>
              <button id="steam-refresh-cache-btn"></button>
              <input id="minecraft-name-input" value="Steve">
              <button id="minecraft-enroll-btn"></button>
              <button id="minecraft-check-btn"></button>
              <button id="minecraft-disconnect-btn"></button>
              <input id="obs-host" value="10.0.0.2">
              <input id="obs-port" value="4456">
              <input id="obs-password" value="">
              <button id="obs-connect-btn"></button>
              <select id="twitchat-branch"><option value="auto">auto</option><option value="beta">beta</option></select>
              <button id="obs-disconnect-btn"></button>
            </div>`);
        render();
    });

    it("drives the Steam personal token", async () => {
        toggle(document.getElementById("steam-token-shared"), true);
        await click("#steam-token-save-btn");
        await click("#steam-token-delete-btn");

        expect(posts()).toEqual([
            ["/channel/enimaloc/settings/steam-personal-token/sharing", { shared: true }],
            ["/channel/enimaloc/settings/steam-personal-token", { token: "KEY", shared: true }],
            ["/channel/enimaloc/settings/steam-personal-token/delete", {}],
        ]);
        expect(refreshed()).toBe(0);
    });

    it("refreshes the Steam profile cache, then re-renders", async () => {
        await click("#steam-refresh-cache-btn");

        expect(posts()).toEqual([["/channel/enimaloc/steam/refresh-profile-cache", {}]]);
        expect(refreshed()).toBe(1);
    });

    it("drives the Minecraft link", async () => {
        await click("#minecraft-enroll-btn");
        await click("#minecraft-check-btn");
        await click("#minecraft-disconnect-btn");

        expect(posts()).toEqual([
            ["/channel/enimaloc/minecraft/enroll", { name: "Steve" }],
            ["/channel/enimaloc/minecraft/sync", {}],
            ["/channel/enimaloc/minecraft/disconnect", {}],
        ]);
    });

    it("saves the OBS settings, keeping the stored password when the field is empty", async () => {
        await click("#obs-connect-btn");
        document.getElementById("obs-password").value = "pw";
        document.getElementById("obs-port").value = "";
        await click("#obs-disconnect-btn");

        expect(posts()).toEqual([
            ["/channel/enimaloc/settings/obs", { enabled: true, host: "10.0.0.2", port: 4456, password: null }],
            ["/channel/enimaloc/settings/obs", { enabled: false, host: "10.0.0.2", port: null, password: "pw" }],
        ]);
        expect(refreshed()).toBe(2);
    });

    it("saves the Twitchat branch, then has obs-session.js apply it", async () => {
        window.CatapultObsSession = { refresh: vi.fn(async () => {}) };
        const select = document.getElementById("twitchat-branch");
        select.value = "beta";
        select.dispatchEvent(new Event("change"));
        await flush();

        expect(posts()).toEqual([["/channel/enimaloc/settings/twitchat/branch", { branch: "beta" }]]);
        expect(CatapultObsSession.refresh).toHaveBeenCalledOnce();
        delete window.CatapultObsSession;
    });

    it("does nothing outside the integrations tab", () => {
        html(`<button id="minecraft-check-btn"></button>`);
        render();
        document.getElementById("minecraft-check-btn").click();

        expect(posted).not.toHaveBeenCalled();
    });
});

describe("channel-settings", () => {
    beforeEach(() => {
        const sw = (cls, value, checked = false) =>
            `<input type="checkbox" class="${cls}" value="${value}" ${checked ? "checked" : ""}>`;
        html(`
            <div id="channel-ccl-settings-form">
              <input type="checkbox" id="ccl-enabled-checkbox" checked>
              ${sw("ccl-block-checkbox", "Drugs", true)}${sw("ccl-block-checkbox", "Gore")}
              <button id="ccl-settings-save-btn"></button>
            </div>
            <div id="channel-tw-settings-form">
              <input type="checkbox" id="tw-enabled-checkbox">
              ${sw("tw-block-checkbox", "spiders")}${sw("tw-block-checkbox", "flashing", true)}
              <button id="tw-settings-save-btn"></button>
            </div>
            <div id="channel-no-game-settings-form">
              <input id="no-game-game-input" value="Just Chatting">
              <input type="hidden" id="no-game-game-id" value="509658">
              <ul id="no-game-game-results"></ul>
              ${sw("no-game-ccl-checkbox", "Gore", true)}
              <input type="checkbox" id="no-game-apply-start" checked>
              <input type="checkbox" id="no-game-apply-no-game">
              <input type="checkbox" id="no-game-apply-end" checked>
              <button id="no-game-settings-save-btn"></button>
            </div>
            <div id="channel-incomplete-fallback-settings-form">
              <input id="incomplete-fallback-game-input" value="">
              <input type="hidden" id="incomplete-fallback-game-id" value="">
              <ul id="incomplete-fallback-game-results"></ul>
              ${sw("incomplete-fallback-ccl-checkbox", "Drugs")}
              <button id="incomplete-fallback-settings-save-btn"></button>
            </div>`);
        render();
    });

    it("saves each card, then re-renders", async () => {
        await click("#ccl-settings-save-btn");
        await click("#tw-settings-save-btn");
        await click("#no-game-settings-save-btn");
        await click("#incomplete-fallback-settings-save-btn");

        expect(posts()).toEqual([
            ["/channel/enimaloc/settings/ccl", { cclEnabled: true, blockedCcls: ["Drugs"] }],
            ["/channel/enimaloc/settings/tws", { enabled: false, blockedTws: ["flashing"] }],
            ["/channel/enimaloc/settings/no-game", {
                twitchGameId: "509658", twitchGameName: "Just Chatting", ccls: ["Gore"],
                applyOnStreamStart: true, applyOnNoGame: false, applyOnStreamEnd: true,
            }],
            ["/channel/enimaloc/settings/incomplete-fallback", { twitchGameId: null, twitchGameName: null, ccls: [] }],
        ]);
        expect(refreshed()).toBe(4);
    });

    it("fills the game id when a search result is picked", async () => {
        vi.useFakeTimers();
        fetch.mockResolvedValueOnce(response(200, [{ id: "7", name: "Doom" }]));
        const input = document.getElementById("incomplete-fallback-game-input");
        input.value = "doom";
        input.dispatchEvent(new Event("input"));
        await vi.runAllTimersAsync();

        document.querySelector("#incomplete-fallback-game-results > *").click();

        expect(document.getElementById("incomplete-fallback-game-id").value).toBe("7");
        expect(input.value).toBe("Doom");
    });

    it("does nothing for viewers without the settings tab", () => {
        html(`<button id="ccl-settings-save-btn"></button>`);
        render();
        document.getElementById("ccl-settings-save-btn").click();

        expect(posted).not.toHaveBeenCalled();
    });
});

describe("channel-dtdd", () => {
    beforeEach(() => {
        html(`
            <div id="channel-dtdd-mapping-card" data-igdb-id="1234">
              <button id="dtdd-validate-btn"></button>
              <button id="dtdd-correct-btn"></button>
              <div id="dtdd-search-dialog" hidden>
                <input id="dtdd-search-input">
                <ul id="dtdd-search-results"></ul>
              </div>
            </div>`);
        render();
    });

    it("validates the current mapping, then re-renders", async () => {
        await click("#dtdd-validate-btn");

        expect(posts()).toEqual([["/channel/dtdd-mapping/validate", { igdbId: "1234" }]]);
        expect(refreshed()).toBe(1);
    });

    it("toggles the correction search", async () => {
        const dialog = document.getElementById("dtdd-search-dialog");

        await click("#dtdd-correct-btn");
        expect(dialog.hidden).toBe(false);

        await click("#dtdd-correct-btn");
        expect(dialog.hidden).toBe(true);
    });

    it("proposes the picked DTDD entry, then re-renders", async () => {
        vi.useFakeTimers();
        fetch.mockResolvedValueOnce(response(200, { results: [{ dtddId: 99, name: "Doom (2016)" }] }));
        const input = document.getElementById("dtdd-search-input");
        input.value = "doom";
        input.dispatchEvent(new Event("input"));
        await vi.runAllTimersAsync();

        document.querySelector("#dtdd-search-results > *").click();
        await vi.runAllTimersAsync();

        expect(fetch).toHaveBeenCalledWith("/channel/dtdd-mapping/search?q=doom");
        expect(posts()).toEqual([["/channel/dtdd-mapping/propose", { igdbId: "1234", dtddId: 99, reason: "correction" }]]);
        expect(refreshed()).toBe(1);
    });

    it("does nothing without a mapping card", () => {
        html(`<button id="dtdd-validate-btn"></button>`);
        render();
        document.getElementById("dtdd-validate-btn").click();

        expect(posted).not.toHaveBeenCalled();
    });
});
