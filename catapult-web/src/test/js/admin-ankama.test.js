/*
 * The admin Ankama tab: the login flow (authorize link, pasted redirect URL, Shield code), the actions and their inline
 * refusals, with the API's own 400 message shown as text.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load, render, response } from "./helpers.js";

const ID = "00000000-0000-0000-0000-00000000000b";
const LOGIN = "00000000-0000-0000-0000-0000000000b1";
const BASE = "/admin/ankama-accounts";
const REDIRECT = "http://127.0.0.1:9001/authorized?code=one-time";
let routes;
let posted;

function row(state) {
    return `
        <div class="admin-ankama-row" data-admin-key="${ID}" data-auth-state="${state}">
            ${state === "REAUTH_REQUIRED" ? `<button class="admin-ankama-reauth" data-id="${ID}"></button>` : ""}
            <button class="admin-ankama-toggle" data-id="${ID}" data-enabled="false"></button>
            <button class="admin-ankama-reset" data-id="${ID}"></button>
            <button class="admin-ankama-delete" data-id="${ID}" data-confirm="Delete Bot?"></button>
            ${state === "CODE_REQUIRED" ? `<div class="admin-ankama-code"><input name="code">
                <button class="admin-ankama-code-submit" data-id="${ID}"></button>
                <button class="admin-ankama-code-resend" data-id="${ID}"></button></div>` : ""}
            <div class="admin-ankama-details"><input name="fillOrder" value="2"><button class="admin-ankama-details-submit" data-id="${ID}"></button></div>
        </div>`;
}

function page(state = "READY") {
    document.body.innerHTML = `
        <mdui-tabs id="admin-tabs" value="ankama">
            <mdui-tab-panel value="ankama">
                <div class="admin-view admin-ankama-view" data-confirm-text="OK" data-cancel-text="No">
                    <div class="admin-keys-card admin-ankama" data-label-conflict="Busy" data-label-unknown="Gone"
                         data-label-unavailable="Down" data-label-updated="Updated" data-label-deleted="Deleted"
                         data-label-invalid="Invalid" data-label-code-sent="Code sent" data-label-code-resent="Code resent"
                         data-label-login-done="Bot logged in">
                        ${row(state)}
                        <button class="admin-ankama-add"></button>
                        <div class="admin-ankama-login" hidden>
                            <a class="admin-ankama-authorize" target="_blank" rel="noopener noreferrer"></a>
                            <input name="redirectUrl">
                            <button class="admin-ankama-login-submit"></button>
                        </div>
                        <p class="admin-ankama-message" hidden></p>
                    </div>
                </div>
            </mdui-tab-panel>
        </mdui-tabs>`;
    render();
}

const message = () => document.querySelector(".admin-ankama-message");
const loginBox = () => document.querySelector(".admin-ankama-login");
const link = () => document.querySelector(".admin-ankama-authorize");
const settle = () => vi.advanceTimersByTimeAsync(0);

async function click(selector) {
    document.querySelector(selector).click();
    await settle();
}

async function startLogin() {
    routes[`${BASE}/login`] = response(200, { loginId: LOGIN, authorizeUrl: "https://auth.ankama.com/authorize?x=1" });
    await click(".admin-ankama-add");
}

beforeEach(async () => {
    vi.useFakeTimers();
    routes = {};
    posted = vi.fn(async url => routes[url] ?? response(200, {}));
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => Promise.resolve()), snackbar: vi.fn() };
    document.documentElement.lang = "en";
    await load("admin-tabs", "admin-ankama");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.patch = vi.fn(async () => {});
    page();
});

afterEach(() => {
    vi.useRealTimers();
});

describe("admin-ankama login flow", () => {
    it("starts a login for a new bot and shows the authorize link, opening a new tab", async () => {
        await startLogin();

        expect(posted).toHaveBeenCalledWith(`${BASE}/login`, {});
        expect(loginBox().hidden).toBe(false);
        expect(link().getAttribute("href")).toBe("https://auth.ankama.com/authorize?x=1");
        expect(link().getAttribute("target")).toBe("_blank");
        expect(link().getAttribute("rel")).toBe("noopener noreferrer");
        expect(link().textContent).toBe("https://auth.ankama.com/authorize?x=1");
    });

    it("never turns a non-https authorize URL into a link", async () => {
        routes[`${BASE}/login`] = response(200, { loginId: LOGIN, authorizeUrl: "javascript:alert(1)" });
        await click(".admin-ankama-add");

        expect(link().hasAttribute("href")).toBe(false);
        expect(loginBox().hidden).toBe(true);
    });

    it("pastes the redirect URL, then empties the field, closes the form and refreshes the list", async () => {
        await startLogin();
        document.querySelector('[name="redirectUrl"]').value = ` ${REDIRECT} `;
        routes[`${BASE}/login/${LOGIN}/complete`] = response(200, { id: ID, authState: "READY" });

        await click(".admin-ankama-login-submit");

        expect(posted).toHaveBeenLastCalledWith(`${BASE}/login/${LOGIN}/complete`, { redirectUrl: REDIRECT });
        expect(document.querySelector('[name="redirectUrl"]').value).toBe("");
        expect(loginBox().hidden).toBe(true);
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Bot logged in" });
        expect(CatapultAdminTabs.patch).toHaveBeenCalledWith(document.querySelector('mdui-tab-panel[value="ankama"]'));
    });

    it("shows the API's 400 message as text, and drops the login the API has used up", async () => {
        await startLogin();
        document.querySelector('[name="redirectUrl"]').value = REDIRECT;
        routes[`${BASE}/login/${LOGIN}/complete`] = response(400, { error: "Ankama refused the login <b>x</b>" });

        await click(".admin-ankama-login-submit");

        expect(message().textContent).toBe("Ankama refused the login <b>x</b>");
        expect(message().querySelector("b")).toBeNull();
        expect(message().classList.contains("admin-ankama-error")).toBe(true);
        expect(document.querySelector('[name="redirectUrl"]').value).toBe("");
        expect(loginBox().hidden).toBe(true);
    });

    it("refuses an empty redirect URL without calling the server", async () => {
        await startLogin();
        posted.mockClear();

        await click(".admin-ankama-login-submit");

        expect(posted).not.toHaveBeenCalled();
        expect(message().textContent).toBe("Invalid");
    });

    it("tells a failed start inline, with the API's message when it has one", async () => {
        routes[`${BASE}/login`] = response(502, {});
        await click(".admin-ankama-add");
        expect(message().textContent).toBe("Down");
        expect(loginBox().hidden).toBe(true);

        routes[`${BASE}/login`] = response(400, { error: "Bad account" });
        await click(".admin-ankama-add");
        expect(message().textContent).toBe("Bad account");
    });

    it("never logs the pasted URL", async () => {
        const spies = ["log", "info", "warn", "error", "debug"].map(name => vi.spyOn(console, name).mockImplementation(() => {}));
        await startLogin();
        document.querySelector('[name="redirectUrl"]').value = REDIRECT;
        routes[`${BASE}/login/${LOGIN}/complete`] = response(400, { error: "no" });
        await click(".admin-ankama-login-submit");

        spies.forEach(spy => expect(JSON.stringify(spy.mock.calls)).not.toContain("one-time"));
        spies.forEach(spy => spy.mockRestore());
    });
});

describe("admin-ankama re-authentication", () => {
    it("shows the re-auth button only on REAUTH_REQUIRED, and starts the login for that bot", async () => {
        expect(document.querySelector(".admin-ankama-reauth")).toBeNull();

        page("REAUTH_REQUIRED");
        routes[`${BASE}/login`] = response(200, { loginId: LOGIN, authorizeUrl: "https://auth.ankama.com/authorize?x=2" });
        await click(".admin-ankama-reauth");

        expect(posted).toHaveBeenCalledWith(`${BASE}/login`, { accountId: ID });
        expect(loginBox().hidden).toBe(false);
        expect(link().getAttribute("href")).toBe("https://auth.ankama.com/authorize?x=2");
    });
});

describe("admin-ankama Shield code", () => {
    beforeEach(() => page("CODE_REQUIRED"));

    it("has the code field only on a CODE_REQUIRED bot", () => {
        expect(document.querySelector(".admin-ankama-code")).not.toBeNull();
        page("READY");
        expect(document.querySelector(".admin-ankama-code")).toBeNull();
    });

    it("sends the code once, emptying the field and refreshing the list", async () => {
        document.querySelector('.admin-ankama-code [name="code"]').value = " 257146 ";
        routes[`${BASE}/${ID}/code`] = response(202, "");

        await click(".admin-ankama-code-submit");

        expect(posted).toHaveBeenLastCalledWith(`${BASE}/${ID}/code`, { code: "257146" });
        expect(document.querySelector('.admin-ankama-code [name="code"]').value).toBe("");
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Code sent" });
        expect(CatapultAdminTabs.patch).toHaveBeenCalled();
    });

    it("shows Ankama's refusal as is, keeping the typed code", async () => {
        document.querySelector('.admin-ankama-code [name="code"]').value = "000000";
        routes[`${BASE}/${ID}/code`] = response(400, { error: "That code is wrong" });

        await click(".admin-ankama-code-submit");

        expect(message().textContent).toBe("That code is wrong");
        expect(document.querySelector('.admin-ankama-code [name="code"]').value).toBe("000000");
        expect(CatapultAdminTabs.patch).not.toHaveBeenCalled();
    });

    it("refuses an empty code, and asks for a new one", async () => {
        await click(".admin-ankama-code-submit");
        expect(posted).not.toHaveBeenCalled();
        expect(message().textContent).toBe("Invalid");

        await click(".admin-ankama-code-resend");
        expect(posted).toHaveBeenLastCalledWith(`${BASE}/${ID}/code/resend`, {});
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Code resent" });
    });

    it("tells a failed resend inline", async () => {
        routes[`${BASE}/${ID}/code/resend`] = response(502, {});
        await click(".admin-ankama-code-resend");
        expect(message().textContent).toBe("Down");
    });
});

describe("admin-ankama actions", () => {
    it("posts toggle and lift-full to their routes", async () => {
        await click(".admin-ankama-toggle");
        expect(posted).toHaveBeenLastCalledWith(`${BASE}/${ID}/enabled`, { enabled: false });
        await click(".admin-ankama-reset");
        expect(posted).toHaveBeenLastCalledWith(`${BASE}/${ID}/reset-limit`, {});
    });

    it("sends the fill order alone, refusing a negative or fractional one", async () => {
        await click(".admin-ankama-details-submit");
        expect(posted).toHaveBeenLastCalledWith(`${BASE}/${ID}/details`, { fillOrder: 2 });

        posted.mockClear();
        document.querySelector('.admin-ankama-details [name="fillOrder"]').value = "-1";
        await click(".admin-ankama-details-submit");
        document.querySelector('.admin-ankama-details [name="fillOrder"]').value = "1.5";
        await click(".admin-ankama-details-submit");
        expect(posted).not.toHaveBeenCalled();
        expect(message().textContent).toBe("Invalid");
    });

    it("tells a 409 and a 404 inline", async () => {
        routes[`${BASE}/${ID}/enabled`] = response(409, {});
        await click(".admin-ankama-toggle");
        expect(message().textContent).toBe("Busy");

        routes[`${BASE}/${ID}/reset-limit`] = response(404, {});
        await click(".admin-ankama-reset");
        expect(message().textContent).toBe("Gone");
    });

    it("deletes after the confirmation only", async () => {
        window.mdui.confirm = vi.fn(() => Promise.reject());
        await click(".admin-ankama-delete");
        expect(posted).not.toHaveBeenCalled();

        window.mdui.confirm = vi.fn(() => Promise.resolve());
        await click(".admin-ankama-delete");
        expect(posted).toHaveBeenCalledWith(`${BASE}/${ID}/delete`, {});
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Deleted" });
    });
});
