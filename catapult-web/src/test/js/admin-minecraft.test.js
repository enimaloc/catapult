/*
 * The admin Keys tab's Minecraft service accounts card: the account actions and the Microsoft
 * device-code flow (add an account, re-authenticate one) with its polling, which stops on an error,
 * on a link that isn't https://, and as soon as the panel's content is replaced or the tab is left.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { load, render, response } from "./helpers.js";

const ID = "00000000-0000-0000-0000-00000000000b";
let routes;
let posted;
let confirmAnswer;

function page() {
    document.body.innerHTML = `
        <mdui-tabs id="admin-tabs" value="keys">
            <mdui-tab-panel value="keys">
                <div class="admin-view admin-keys" data-confirm-text="OK" data-cancel-text="No">
                    <div id="admin-minecraft">
                        <div class="admin-keys-card admin-minecraft" data-label-linked="Linked" data-label-unknown="Gone"
                             data-label-unavailable="Down" data-label-updated="Updated" data-label-deleted="Deleted"
                             data-label-created="Created" data-label-reauthed="Reauthed" data-label-code="Enter the code"
                             data-label-pending="Waiting" data-label-insecure="Unsafe link" data-label-failed="Failed"
                             data-label-expired="Expired">
                            <div class="admin-minecraft-row">
                                <button class="admin-minecraft-reauth" data-id="${ID}"></button>
                                <button class="admin-minecraft-toggle" data-id="${ID}" data-enabled="true"></button>
                                <button class="admin-minecraft-reset" data-id="${ID}"></button>
                                <button class="admin-minecraft-delete" data-id="${ID}" data-confirm="Delete Off?"></button>
                                <div class="admin-minecraft-flow"></div>
                            </div>
                            <div class="admin-minecraft-enroll">
                                <input name="label">
                                <button class="admin-minecraft-add"></button>
                                <div class="admin-minecraft-flow"></div>
                            </div>
                        </div>
                    </div>
                </div>
            </mdui-tab-panel>
            <mdui-tab-panel value="broadcast"></mdui-tab-panel>
        </mdui-tabs>`;
    render();
}

const panel = () => document.querySelector('mdui-tab-panel[value="keys"]');
const addFlow = () => document.querySelector(".admin-minecraft-enroll .admin-minecraft-flow");
const rowFlow = () => document.querySelector(".admin-minecraft-row .admin-minecraft-flow");
const polls = () => posted.mock.calls.filter(([url]) => url.endsWith("/create") || url.endsWith("/reauth"));
const settle = () => vi.advanceTimersByTimeAsync(0);

async function click(selector) {
    document.querySelector(selector).click();
    await settle();
}

beforeEach(async () => {
    vi.useFakeTimers();
    confirmAnswer = true;
    routes = {
        "/admin/minecraft-accounts/device-code": [response(200, {
            deviceCode: "dc-1", userCode: "ABCD-1234", verificationUri: "https://www.microsoft.com/link", interval: 1 })],
    };
    posted = vi.fn(async url => {
        const queue = routes[url];
        if (!queue) return response(200, {});
        return queue.length > 1 ? queue.shift() : queue[0];
    });
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())), snackbar: vi.fn() };
    await load("admin-tabs", "admin-minecraft");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
});

afterEach(() => {
    vi.useRealTimers();
});

describe("admin-minecraft device-code flow", () => {
    it("shows the user code and an https link, then polls every max(3, interval) s until 200 and reloads", async () => {
        routes["/admin/minecraft-accounts/create"] = [response(202, {}), response(200, { minecraftUsername: "Bot_mc", label: "Bot1" })];
        document.querySelector('[name="label"]').value = " Bot1 ";

        await click(".admin-minecraft-add");

        expect(posted).toHaveBeenCalledWith("/admin/minecraft-accounts/device-code", {});
        expect(addFlow().querySelector(".admin-minecraft-user-code").textContent).toBe("ABCD-1234");
        const link = addFlow().querySelector("a");
        expect(link.getAttribute("href")).toBe("https://www.microsoft.com/link");
        expect(link.target).toBe("_blank");
        expect(link.rel).toContain("noopener");
        expect(document.querySelector(".admin-minecraft-add").disabled).toBe(true);

        await vi.advanceTimersByTimeAsync(2999);
        expect(polls()).toHaveLength(0);
        await vi.advanceTimersByTimeAsync(1);
        expect(polls()).toHaveLength(1);
        expect(posted).toHaveBeenLastCalledWith("/admin/minecraft-accounts/create", { deviceCode: "dc-1", label: "Bot1" });
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();

        await vi.advanceTimersByTimeAsync(3000);
        expect(polls()).toHaveLength(2);
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Created" });
        expect(CatapultAdminTabs.reload).toHaveBeenCalledWith(panel());

        await vi.advanceTimersByTimeAsync(30000);
        expect(polls()).toHaveLength(2);
    });

    it("keeps a longer interval as given", async () => {
        routes["/admin/minecraft-accounts/device-code"] = [response(200, {
            deviceCode: "dc-1", userCode: "ABCD-1234", verificationUri: "https://www.microsoft.com/link", interval: 5 })];
        routes["/admin/minecraft-accounts/create"] = [response(202, {})];

        await click(".admin-minecraft-add");
        await vi.advanceTimersByTimeAsync(4999);
        expect(polls()).toHaveLength(0);
        await vi.advanceTimersByTimeAsync(1);
        expect(polls()).toHaveLength(1);
        expect(posted).toHaveBeenLastCalledWith("/admin/minecraft-accounts/create", { deviceCode: "dc-1" });
    });

    it("stops on an error status and shows the message", async () => {
        routes[`/admin/minecraft-accounts/${ID}/reauth`] = [response(202, {}), response(502, { message: "chain failed" })];

        await click(".admin-minecraft-reauth");
        await vi.advanceTimersByTimeAsync(6000);

        expect(posted).toHaveBeenLastCalledWith(`/admin/minecraft-accounts/${ID}/reauth`, { deviceCode: "dc-1" });
        expect(polls()).toHaveLength(2);
        expect(rowFlow().textContent).toContain("Failed");
        expect(rowFlow().textContent).toContain("chain failed");
        expect(rowFlow().querySelector("a")).toBeNull();
        expect(document.querySelector(".admin-minecraft-reauth").disabled).toBe(false);
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();

        await vi.advanceTimersByTimeAsync(30000);
        expect(polls()).toHaveLength(2);
    });

    it("says why the device code couldn't be had", async () => {
        routes["/admin/minecraft-accounts/device-code"] = [response(502, "")];

        await click(".admin-minecraft-add");
        await vi.advanceTimersByTimeAsync(30000);

        expect(addFlow().textContent).toContain("Failed");
        expect(polls()).toHaveLength(0);
        expect(document.querySelector(".admin-minecraft-add").disabled).toBe(false);
    });

    it("does not link a non-https verification URI and does not start polling", async () => {
        for (const uri of ["http://www.microsoft.com/link", "javascript:alert(1)", "//evil.test/link", ""]) {
            routes["/admin/minecraft-accounts/device-code"] = [response(200, {
                deviceCode: "dc-1", userCode: "ABCD-1234", verificationUri: uri, interval: 5 })];

            await click(".admin-minecraft-add");
            await vi.advanceTimersByTimeAsync(30000);

            expect(addFlow().querySelector("a")).toBeNull();
            expect(addFlow().textContent).toContain("Unsafe link");
            expect(addFlow().textContent).not.toContain(uri || "\u0000");
            expect(polls()).toHaveLength(0);
            expect(document.querySelector(".admin-minecraft-add").disabled).toBe(false);
        }
    });

    it("stops polling once the panel content is replaced", async () => {
        routes["/admin/minecraft-accounts/create"] = [response(202, {})];
        await click(".admin-minecraft-add");
        await vi.advanceTimersByTimeAsync(3000);
        expect(polls()).toHaveLength(1);

        panel().innerHTML = "<p>another view</p>";
        await vi.advanceTimersByTimeAsync(30000);

        expect(polls()).toHaveLength(1);
    });

    it("stops polling when another admin tab is shown", async () => {
        routes["/admin/minecraft-accounts/create"] = [response(202, {})];
        await click(".admin-minecraft-add");

        const tabs = document.getElementById("admin-tabs");
        tabs.value = "broadcast";
        tabs.dispatchEvent(new Event("change", { bubbles: true }));
        await vi.advanceTimersByTimeAsync(30000);

        expect(polls()).toHaveLength(0);
        expect(addFlow().textContent).toBe("");
        expect(document.querySelector(".admin-minecraft-add").disabled).toBe(false);
    });
});

describe("admin-minecraft actions", () => {
    it("toggles, resets the limit and deletes, then reloads", async () => {
        await click(".admin-minecraft-toggle");
        expect(posted).toHaveBeenLastCalledWith(`/admin/minecraft-accounts/${ID}/enabled`, { enabled: true });
        await click(".admin-minecraft-reset");
        expect(posted).toHaveBeenLastCalledWith(`/admin/minecraft-accounts/${ID}/reset-limit`, {});
        await click(".admin-minecraft-delete");
        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Delete Off?" }));
        expect(posted).toHaveBeenLastCalledWith(`/admin/minecraft-accounts/${ID}/delete`, {});
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Deleted" });
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(3);
    });

    it("tells a linked account apart from an unreachable api, and posts once on a double click", async () => {
        routes[`/admin/minecraft-accounts/${ID}/delete`] = [response(409, "")];
        await click(".admin-minecraft-delete");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Linked");
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();

        routes[`/admin/minecraft-accounts/${ID}/enabled`] = [response(502, "")];
        const toggle = document.querySelector(".admin-minecraft-toggle");
        toggle.click();
        toggle.click();
        await settle();
        expect(posted.mock.calls.filter(([url]) => url.endsWith("/enabled"))).toHaveLength(1);
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(toggle.disabled).toBe(false);
    });

    it("does not delete when the confirmation is declined", async () => {
        confirmAnswer = false;
        await click(".admin-minecraft-delete");

        expect(posted).not.toHaveBeenCalled();
    });
});
