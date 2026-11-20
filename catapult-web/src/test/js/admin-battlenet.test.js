/*
 * The admin Keys tab's Battle.net bot pool card: the actions and their inline refusals, the 2FA code and
 * password fields (sent once, then emptied), and the 3 s refresh while a bot is NEW or waits for its code.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load, render, response } from "./helpers.js";

const ID = "00000000-0000-0000-0000-00000000000b";
let routes;
let posted;

function row(state, waitingForCode) {
    return `
        <div class="admin-battlenet-row" data-auth-state="${state}" data-waiting-code="${waitingForCode}">
            <button class="admin-battlenet-reauth" data-id="${ID}"></button>
            <button class="admin-battlenet-toggle" data-id="${ID}" data-enabled="false"></button>
            <button class="admin-battlenet-reset" data-id="${ID}"></button>
            <button class="admin-battlenet-delete" data-id="${ID}" data-confirm="Delete Bot?"></button>
            <div class="admin-battlenet-code"><input name="code"><button class="admin-battlenet-code-submit" data-id="${ID}"></button></div>
            <div class="admin-battlenet-details"><input name="fillOrder" value="2"><button class="admin-battlenet-details-submit" data-id="${ID}"></button></div>
            <div class="admin-battlenet-password"><input name="password"><button class="admin-battlenet-password-submit" data-id="${ID}"></button></div>
        </div>`;
}

function page(state = "READY", waitingForCode = state === "CODE_REQUIRED") {
    document.body.innerHTML = `
        <mdui-tabs id="admin-tabs" value="keys">
            <mdui-tab-panel value="keys">
                <div class="admin-view admin-keys" data-confirm-text="OK" data-cancel-text="No">
                    <div id="admin-battlenet">
                        <div class="admin-keys-card admin-battlenet" data-label-conflict="Busy" data-label-unknown="Gone"
                             data-label-unavailable="Down" data-label-updated="Updated" data-label-deleted="Deleted"
                             data-label-created="Created" data-label-code-sent="Code sent" data-label-reauthed="Reauthed"
                             data-label-invalid="Invalid">
                            ${row(state, waitingForCode)}
                            <div class="admin-battlenet-enroll">
                                <input name="email"><input name="password" type="password">
                                <button class="admin-battlenet-add"></button>
                            </div>
                            <p class="admin-battlenet-message" hidden></p>
                        </div>
                    </div>
                </div>
            </mdui-tab-panel>
            <mdui-tab-panel value="broadcast"></mdui-tab-panel>
        </mdui-tabs>`;
    render();
}

const message = () => document.querySelector(".admin-battlenet-message");
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
    await load("admin-tabs", "admin-battlenet");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.patch = vi.fn(async () => {});
    page();
});

afterEach(() => {
    vi.useRealTimers();
});

describe("admin-battlenet actions", () => {
    it("adds a bot with the typed credentials in the body, then empties the fields and patches", async () => {
        routes["/admin/battlenet-accounts"] = response(202, {});
        document.querySelector('.admin-battlenet-enroll [name="email"]').value = " bot@example.com ";
        document.querySelector('.admin-battlenet-enroll [name="password"]').value = "s3cret";

        await click(".admin-battlenet-add");

        expect(posted).toHaveBeenCalledWith("/admin/battlenet-accounts", { email: "bot@example.com", password: "s3cret" });
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Created" });
        expect(document.querySelector('.admin-battlenet-enroll [name="password"]').value).toBe("");
        expect(CatapultAdminTabs.patch).toHaveBeenCalledWith(document.querySelector('mdui-tab-panel[value="keys"]'));
    });

    it("refuses an incomplete add form without calling the server", async () => {
        document.querySelector('.admin-battlenet-enroll [name="email"]').value = "bot@example.com";

        await click(".admin-battlenet-add");

        expect(posted).not.toHaveBeenCalled();
        expect(message().textContent).toBe("Invalid");
        expect(message().hidden).toBe(false);
    });

    it("sends the 2FA code and the new password once, emptying the fields", async () => {
        document.querySelector('.admin-battlenet-code [name="code"]').value = " 123456 ";
        await click(".admin-battlenet-code-submit");
        expect(posted).toHaveBeenLastCalledWith(`/admin/battlenet-accounts/${ID}/code`, { code: "123456" });
        expect(document.querySelector('.admin-battlenet-code [name="code"]').value).toBe("");

        document.querySelector('.admin-battlenet-password [name="password"]').value = "n3w";
        await click(".admin-battlenet-password-submit");
        expect(posted).toHaveBeenLastCalledWith(`/admin/battlenet-accounts/${ID}/password`, { password: "n3w" });
        expect(document.querySelector('.admin-battlenet-password [name="password"]').value).toBe("");
    });

    it("posts reauth, toggle and lift-full to their routes", async () => {
        await click(".admin-battlenet-reauth");
        expect(posted).toHaveBeenLastCalledWith(`/admin/battlenet-accounts/${ID}/reauth`, {});
        await click(".admin-battlenet-toggle");
        expect(posted).toHaveBeenLastCalledWith(`/admin/battlenet-accounts/${ID}/enabled`, { enabled: false });
        await click(".admin-battlenet-reset");
        expect(posted).toHaveBeenLastCalledWith(`/admin/battlenet-accounts/${ID}/reset-limit`, {});
    });

    it("tells a 409 and a 404 inline, keeping the typed code on a refusal", async () => {
        routes[`/admin/battlenet-accounts/${ID}/code`] = response(409, {});
        document.querySelector('.admin-battlenet-code [name="code"]').value = "123456";

        await click(".admin-battlenet-code-submit");
        expect(message().textContent).toBe("Busy");
        expect(message().classList.contains("admin-battlenet-error")).toBe(true);
        expect(document.querySelector('.admin-battlenet-code [name="code"]').value).toBe("123456");

        routes[`/admin/battlenet-accounts/${ID}/reauth`] = response(404, {});
        await click(".admin-battlenet-reauth");
        expect(message().textContent).toBe("Gone");

        routes[`/admin/battlenet-accounts/${ID}/reset-limit`] = response(502, {});
        await click(".admin-battlenet-reset");
        expect(message().textContent).toBe("Down");
    });

    it("deletes after the confirmation only", async () => {
        window.mdui.confirm = vi.fn(() => Promise.reject());
        await click(".admin-battlenet-delete");
        expect(posted).not.toHaveBeenCalled();

        window.mdui.confirm = vi.fn(() => Promise.resolve());
        await click(".admin-battlenet-delete");
        expect(posted).toHaveBeenCalledWith(`/admin/battlenet-accounts/${ID}/delete`, {});
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Deleted" });
    });
});

describe("admin-battlenet details", () => {
    it("sends the fill order alone, refusing a negative or fractional one", async () => {
        await click(".admin-battlenet-details-submit");
        expect(posted).toHaveBeenLastCalledWith(`/admin/battlenet-accounts/${ID}/details`, { fillOrder: 2 });

        posted.mockClear();
        document.querySelector('.admin-battlenet-details [name="fillOrder"]').value = "-1";
        await click(".admin-battlenet-details-submit");
        document.querySelector('.admin-battlenet-details [name="fillOrder"]').value = "1.5";
        await click(".admin-battlenet-details-submit");
        expect(posted).not.toHaveBeenCalled();
        expect(message().textContent).toBe("Invalid");
    });
});

describe("admin-battlenet polling", () => {
    it("patches the tab every 3 s while a bot waits for its code, and stops once none does", async () => {
        page("CODE_REQUIRED");
        await vi.advanceTimersByTimeAsync(2999);
        expect(CatapultAdminTabs.patch).not.toHaveBeenCalled();
        await vi.advanceTimersByTimeAsync(1);
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(1);
        await vi.advanceTimersByTimeAsync(3000);
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(2);

        document.querySelector(".admin-battlenet-row").dataset.authState = "READY";
        await vi.advanceTimersByTimeAsync(3000);
        const calls = CatapultAdminTabs.patch.mock.calls.length;
        await vi.advanceTimersByTimeAsync(30000);
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(calls);
    });

    it("does not poll a CODE_REQUIRED bot no login waits for", async () => {
        page("CODE_REQUIRED", false);
        await vi.advanceTimersByTimeAsync(30000);
        expect(CatapultAdminTabs.patch).not.toHaveBeenCalled();
    });

    it("does not poll when every bot is settled", async () => {
        page("READY");
        await vi.advanceTimersByTimeAsync(30000);
        expect(CatapultAdminTabs.patch).not.toHaveBeenCalled();
    });

    it("polls a NEW bot too, and stops when another admin tab is shown", async () => {
        page("NEW");
        await vi.advanceTimersByTimeAsync(3000);
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(1);

        const tabs = document.getElementById("admin-tabs");
        tabs.value = "broadcast";
        tabs.dispatchEvent(new Event("change"));
        const calls = CatapultAdminTabs.patch.mock.calls.length;
        await vi.advanceTimersByTimeAsync(30000);
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(calls);
    });

    it("does not restart when a not-yet-visited tab is opened, nor after a tick in flight at leave time", async () => {
        page("CODE_REQUIRED");
        let finish;
        CatapultAdminTabs.patch = vi.fn(() => new Promise(resolve => { finish = resolve; }));
        await vi.advanceTimersByTimeAsync(3000); // a tick is in flight
        const tabs = document.getElementById("admin-tabs");
        tabs.value = "broadcast";
        tabs.dispatchEvent(new Event("change"));
        document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "broadcast" } }));
        finish();
        await vi.advanceTimersByTimeAsync(0);
        await vi.advanceTimersByTimeAsync(30000);
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(1);

        tabs.value = "keys"; // back on Keys: it resumes
        tabs.dispatchEvent(new Event("change"));
        await vi.advanceTimersByTimeAsync(3000);
        expect(CatapultAdminTabs.patch).toHaveBeenCalledTimes(2);
    });
});

describe("admin-battlenet free BGS call", () => {
    const CATALOG = [
        { name: "svc.A", hash: "0x00000001", methods: [{ name: "Ping", id: 1, noResponse: false }] },
        { name: "svc.B", hash: "0x00000002", methods: [{ name: "Update", id: 6, noResponse: false },
            { name: "Fire", id: 7, noResponse: true }] }
    ];

    function bgsPanel() {
        const panel = document.createElement("details");
        panel.className = "admin-battlenet-bgs";
        panel.innerHTML = `
            <summary class="admin-battlenet-bgs-call">BGS</summary>
            <input name="bgsMode" value="known">
            <div class="admin-battlenet-bgs-known"><select name="bgsService"></select><select name="bgsMethod"></select>
                <textarea name="bgsText"></textarea></div>
            <div class="admin-battlenet-bgs-raw" hidden><input name="bgsHash"><input name="bgsMethodId"><textarea name="bgsHex"></textarea>
                <input type="checkbox" name="bgsCiid" checked></div>
            <button class="admin-battlenet-bgs-send" data-id="${ID}" data-label-sending="Sending" data-label-no-response="no reply"
                    data-label-pushes="Pushes" data-label-no-push="None">Send</button>
            <div class="admin-battlenet-bgs-result" hidden><p class="admin-battlenet-bgs-status"></p>
                <pre class="admin-battlenet-bgs-reply"></pre><input name="bgsFilter"><ul class="admin-battlenet-bgs-pushes"></ul></div>`;
        document.querySelector(".admin-battlenet-row").append(panel);
        return panel;
    }

    async function open(panel) {
        panel.open = true;
        panel.dispatchEvent(new Event("toggle"));
        await settle();
    }

    beforeEach(() => {
        window.fetch = vi.fn(async () => response(200, CATALOG));
    });

    it("fills the services and the methods of the first one when the panel opens, loading the catalogue once", async () => {
        const panel = bgsPanel();
        await open(panel);

        expect([...panel.querySelector('[name="bgsService"]').options].map(o => o.value)).toEqual(["svc.A", "svc.B"]);
        expect([...panel.querySelector('[name="bgsMethod"]').options].map(o => o.value)).toEqual(["Ping"]);
        const service = panel.querySelector('[name="bgsService"]');
        service.value = "svc.B";
        service.dispatchEvent(new Event("change", { bubbles: true }));
        await settle();
        expect([...panel.querySelector('[name="bgsMethod"]').options].map(o => o.value)).toEqual(["Update", "Fire"]);
        expect(window.fetch).toHaveBeenCalledTimes(1);
    });

    it("refills lists a tab patch emptied, when the panel is opened again", async () => {
        const panel = bgsPanel();
        await open(panel);
        // the morph of a tab patch empties the selects and keeps the panel's attributes
        panel.querySelector('[name="bgsService"]').replaceChildren();
        panel.querySelector('[name="bgsMethod"]').replaceChildren();
        panel.open = false;

        await open(panel);

        expect(panel.querySelector('[name="bgsService"]').options).toHaveLength(2);
        expect(panel.querySelector('[name="bgsMethod"]').value).toBe("Ping");
    });

    it("posts a known call and shows the status, the reply and the pushes through text only", async () => {
        const panel = bgsPanel();
        await open(panel);
        panel.querySelector('[name="bgsText"]').value = "x: 1";
        routes[`/admin/battlenet-accounts/${ID}/bgs-call`] = response(200, {
            status: 0, durationMs: 12, replyText: "", replyHex: "",
            pushes: [{ service: "svc.L", method: "On", text: "<b>x</b>", hex: "08" }], error: null
        });

        await click(".admin-battlenet-bgs-send");

        expect(posted).toHaveBeenCalledWith(`/admin/battlenet-accounts/${ID}/bgs-call`,
            { service: "svc.A", method: "Ping", text: "x: 1" });
        expect(panel.querySelector(".admin-battlenet-bgs-status").textContent).toBe("status 0 (OK) · 12 ms");
        expect(panel.querySelector(".admin-battlenet-bgs-result").hidden).toBe(false);
        expect(panel.querySelector(".admin-battlenet-bgs-pushes pre").textContent).toBe("<b>x</b>");
        expect(panel.querySelector(".admin-battlenet-bgs-pushes b")).toBeNull();
    });

    it("tells a refusal inline with the API's message", async () => {
        const panel = bgsPanel();
        await open(panel);
        routes[`/admin/battlenet-accounts/${ID}/bgs-call`] = response(409, { error: "A call is already running" });

        await click(".admin-battlenet-bgs-send");

        expect(message().textContent).toBe("A call is already running");
        expect(panel.querySelector(".admin-battlenet-bgs-result").hidden).toBe(true);
    });
});

