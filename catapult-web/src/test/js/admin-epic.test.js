/*
 * The Epic bots card of the admin Keys & limits tab: the paste flow (add a bot, re-authenticate), the API's own 400
 * message shown as text, the re-authenticate button only on REAUTH_REQUIRED and the row actions.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load, render, response } from "./helpers.js";

const ID = "00000000-0000-0000-0000-00000000000b";
const BASE = "/admin/epic-accounts";
const CODE_URL = "https://www.epicgames.com/id/api/redirect?clientId=x&responseType=code";
const PASTED = '{"redirectUrl":"x","authorizationCode":"0123456789abcdef0123456789abcdef"}';
let routes;
let posted;

function row(state) {
    return `
        <div class="admin-epic-row" data-admin-key="${ID}" data-auth-state="${state}">
            ${state === "REAUTH_REQUIRED" ? `<button class="admin-epic-reauth" data-id="${ID}"></button>` : ""}
            <button class="admin-epic-toggle" data-id="${ID}" data-enabled="false"></button>
            <button class="admin-epic-reset" data-id="${ID}"></button>
            <button class="admin-epic-delete" data-id="${ID}" data-confirm="Delete Bot?"></button>
            <div class="admin-epic-details"><input name="fillOrder" value="2"><button class="admin-epic-details-submit" data-id="${ID}"></button></div>
        </div>`;
}

function page(state = "READY") {
    document.body.innerHTML = `
        <mdui-tabs id="admin-tabs" value="keys">
            <mdui-tab-panel value="keys">
                <div class="admin-view admin-keys" data-confirm-text="OK" data-cancel-text="No">
                    <div class="admin-keys-card admin-epic" data-label-conflict="Busy" data-label-unknown="Gone"
                         data-label-unavailable="Down" data-label-updated="Updated" data-label-deleted="Deleted"
                         data-label-invalid="Invalid" data-label-login-done="Bot logged in">
                        ${row(state)}
                        <button class="admin-epic-add"></button>
                        <div class="admin-epic-login" hidden>
                            <a class="admin-epic-code-link" href="${CODE_URL}" target="_blank" rel="noopener noreferrer"></a>
                            <input name="code">
                            <button class="admin-epic-login-submit"></button>
                        </div>
                        <p class="admin-epic-message" hidden></p>
                    </div>
                </div>
            </mdui-tab-panel>
        </mdui-tabs>`;
    render();
}

const message = () => document.querySelector(".admin-epic-message");
const loginBox = () => document.querySelector(".admin-epic-login");
const field = () => document.querySelector('.admin-epic-login [name="code"]');
const settle = () => vi.advanceTimersByTimeAsync(0);

async function click(selector) {
    document.querySelector(selector).click();
    await settle();
}

beforeEach(async () => {
    vi.useFakeTimers();
    routes = {};
    posted = vi.fn(async url => routes[url] ?? response(200, {}));
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => Promise.resolve()), snackbar: vi.fn() };
    document.documentElement.lang = "en";
    await load("admin-tabs", "admin-epic");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.patch = vi.fn(async () => {});
    page();
});

afterEach(() => {
    vi.useRealTimers();
});

describe("admin-epic add a bot", () => {
    it("opens the login box on Add a bot, whose code link opens a new tab without opener", async () => {
        await click(".admin-epic-add");

        expect(loginBox().hidden).toBe(false);
        const link = document.querySelector(".admin-epic-code-link");
        expect(link.getAttribute("href")).toBe(CODE_URL);
        expect(link.getAttribute("target")).toBe("_blank");
        expect(link.getAttribute("rel")).toBe("noopener noreferrer");
    });

    it("posts what was pasted, then empties the field, closes the box and refreshes the list", async () => {
        await click(".admin-epic-add");
        field().value = ` ${PASTED} `;
        routes[BASE] = response(200, { id: ID, authState: "READY" });

        await click(".admin-epic-login-submit");

        expect(posted).toHaveBeenLastCalledWith(BASE, { code: PASTED });
        expect(field().value).toBe("");
        expect(loginBox().hidden).toBe(true);
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Bot logged in" });
        expect(CatapultAdminTabs.patch).toHaveBeenCalledWith(document.querySelector('mdui-tab-panel[value="keys"]'));
    });

    it("shows the API's 400 message as text, keeping the box open for a new paste", async () => {
        await click(".admin-epic-add");
        field().value = "0123456789abcdef0123456789abcdef";
        routes[BASE] = response(400, { error: "this code expired <b>x</b>" });

        await click(".admin-epic-login-submit");

        expect(message().textContent).toBe("this code expired <b>x</b>");
        expect(message().querySelector("b")).toBeNull();
        expect(message().classList.contains("admin-epic-error")).toBe(true);
        expect(field().value).toBe("");
        expect(loginBox().hidden).toBe(false);
        expect(CatapultAdminTabs.patch).not.toHaveBeenCalled();
    });

    it("refuses an empty paste without calling the server", async () => {
        await click(".admin-epic-add");
        posted.mockClear();

        await click(".admin-epic-login-submit");

        expect(posted).not.toHaveBeenCalled();
        expect(message().textContent).toBe("Invalid");
    });

    it("tells an unreachable server inline and keeps the pasted text", async () => {
        window.CatapultCsrf.postJson = vi.fn(async () => { throw new Error("offline"); });
        await click(".admin-epic-add");
        field().value = PASTED;

        await click(".admin-epic-login-submit");

        expect(message().textContent).toBe("Down");
        expect(field().value).toBe(PASTED);
    });

    it("never logs the pasted code", async () => {
        const spies = ["log", "info", "warn", "error", "debug"].map(name => vi.spyOn(console, name).mockImplementation(() => {}));
        await click(".admin-epic-add");
        field().value = PASTED;
        routes[BASE] = response(400, { error: "no" });
        await click(".admin-epic-login-submit");

        spies.forEach(spy => expect(JSON.stringify(spy.mock.calls)).not.toContain("0123456789abcdef"));
        spies.forEach(spy => spy.mockRestore());
    });
});

describe("admin-epic re-authentication", () => {
    it("shows the re-auth button only on REAUTH_REQUIRED", () => {
        expect(document.querySelector(".admin-epic-reauth")).toBeNull();
        page("REAUTH_REQUIRED");
        expect(document.querySelector(".admin-epic-reauth")).not.toBeNull();
    });

    it("posts the code with the bot's id, and a later Add a bot forgets that id", async () => {
        page("REAUTH_REQUIRED");
        await click(".admin-epic-reauth");
        expect(loginBox().hidden).toBe(false);
        field().value = PASTED;

        await click(".admin-epic-login-submit");
        expect(posted).toHaveBeenLastCalledWith(BASE, { code: PASTED, accountId: ID });

        await click(".admin-epic-reauth");
        await click(".admin-epic-add");
        field().value = PASTED;
        await click(".admin-epic-login-submit");
        expect(posted).toHaveBeenLastCalledWith(BASE, { code: PASTED });
    });
});

describe("admin-epic actions", () => {
    it("posts toggle and lift-full to their routes", async () => {
        await click(".admin-epic-toggle");
        expect(posted).toHaveBeenLastCalledWith(`${BASE}/${ID}/enabled`, { enabled: false });
        expect(CatapultAdminTabs.patch).toHaveBeenCalled();
        await click(".admin-epic-reset");
        expect(posted).toHaveBeenLastCalledWith(`${BASE}/${ID}/reset-limit`, {});
    });

    it("sends the fill order alone, refusing a negative or fractional one", async () => {
        await click(".admin-epic-details-submit");
        expect(posted).toHaveBeenLastCalledWith(`${BASE}/${ID}/details`, { fillOrder: 2 });

        posted.mockClear();
        document.querySelector('.admin-epic-details [name="fillOrder"]').value = "-1";
        await click(".admin-epic-details-submit");
        document.querySelector('.admin-epic-details [name="fillOrder"]').value = "1.5";
        await click(".admin-epic-details-submit");
        expect(posted).not.toHaveBeenCalled();
        expect(message().textContent).toBe("Invalid");
    });

    it("tells a 409 and a 404 inline", async () => {
        routes[`${BASE}/${ID}/enabled`] = response(409, {});
        await click(".admin-epic-toggle");
        expect(message().textContent).toBe("Busy");

        routes[`${BASE}/${ID}/reset-limit`] = response(404, {});
        await click(".admin-epic-reset");
        expect(message().textContent).toBe("Gone");
    });

    it("deletes after the confirmation only", async () => {
        window.mdui.confirm = vi.fn(() => Promise.reject());
        await click(".admin-epic-delete");
        expect(posted).not.toHaveBeenCalled();

        window.mdui.confirm = vi.fn(() => Promise.resolve());
        await click(".admin-epic-delete");
        expect(posted).toHaveBeenCalledWith(`${BASE}/${ID}/delete`, {});
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Deleted" });
    });
});
