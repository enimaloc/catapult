/*
 * The admin DTDD caches tab: an entry fetched again (then the results patched), the bulk refetch started once
 * confirmed or stopped, and a running one followed by patching the summary every few seconds while the tab shows it.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render } from "./helpers.js";

const POLL_MS = 3000;

let tabs;
let sent;
let confirmAnswer;
let jobState;

function summary(state) {
    html(`
        <mdui-tabs id="admin-tabs"></mdui-tabs>
        <mdui-tab-panel value="dtdd-caches">
            <div class="admin-view admin-dtdd-caches" data-start-confirm="Refetch all?" data-start-description="Costs requests">
                <div data-admin-value="job" data-state="${state}">
                    <button id="admin-dc-start"></button>
                    <button id="admin-dc-cancel"></button>
                </div>
            </div>
        </mdui-tab-panel>`);
    document.getElementById("admin-tabs").value = "dtdd-caches";
    render();
}

function entries(kind, key) {
    html(`
        <mdui-tabs id="admin-tabs"></mdui-tabs>
        <mdui-tab-panel value="dtdd-caches">
            <div class="admin-view admin-dtdd-cache-entries" data-kind="${kind}" data-refetched="Fetched again.">
                <div data-admin-results>
                    <div class="admin-row" data-admin-key="${key}">
                        <button class="admin-dc-detail" data-key="${key}"></button>
                        <button class="admin-dc-refetch" data-key="${key}"></button>
                    </div>
                </div>
                <mdui-dialog id="admin-dc-dialog"><div class="admin-dialog-body"></div><button class="admin-dialog-close"></button></mdui-dialog>
            </div>
        </mdui-tab-panel>`);
    document.getElementById("admin-tabs").value = "dtdd-caches";
    render();
}

const panel = () => document.querySelector('mdui-tab-panel[value="dtdd-caches"]');
const snackbars = () => window.mdui.snackbar.mock.calls.map(([options]) => options.message);

/** Lets the click's async handler run (fake timers: a 0 ms advance, not the follow-up's). */
async function click(selector) {
    document.querySelector(selector).click();
    await vi.advanceTimersByTimeAsync(0);
}

beforeEach(async () => {
    vi.useFakeTimers();
    confirmAnswer = true;
    sent = "ok";
    jobState = null;
    tabs = {
        post: vi.fn(async () => sent),
        confirmed: vi.fn(async () => confirmAnswer),
        // a patch shows the job as catapult-api answers it now
        patch: vi.fn(async () => {
            const job = document.querySelector('[data-admin-value="job"]');
            if (job && jobState) job.dataset.state = jobState;
        })
    };
    await load("channel-page", "admin-dtdd-caches");
    window.CatapultAdminTabs = tabs;
    window.mdui = { ...(window.mdui ?? {}), snackbar: vi.fn() };
});

afterEach(() => {
    vi.useRealTimers();
});

describe("admin-dtdd-caches", () => {
    it("fetches an entry again by its key, then patches the results", async () => {
        entries("searches", "halo infinite");
        await click(".admin-dc-refetch");

        const [root, button, url, body] = tabs.post.mock.calls[0];
        expect(root.dataset.kind).toBe("searches");
        expect(button.dataset.key).toBe("halo infinite");
        expect(url).toBe("/admin/dtdd-caches/searches/refetch?key=halo+infinite");
        expect(body).toEqual({});
        expect(tabs.patch).toHaveBeenCalledWith(panel());
        expect(snackbars()).toContain("Fetched again.");
        expect(button.disabled).toBe(false);
    });

    it("opens an entry in full in the dialog, and closes it", async () => {
        entries("searches", "halo infinite");
        window.fetch = vi.fn(async () => ({ ok: true, status: 200, text: async () => "<p class='full'>Halo</p>" }));
        await click(".admin-dc-detail");

        expect(window.fetch).toHaveBeenCalledWith("/spa/admin/dtdd-caches/searches/entry?key=halo+infinite");
        expect(document.querySelector("#admin-dc-dialog .full").textContent).toBe("Halo");
        expect(document.getElementById("admin-dc-dialog").open).toBe(true);

        await click(".admin-dialog-close");
        expect(document.getElementById("admin-dc-dialog").open).toBe(false);
    });

    it("tells why an entry can't be opened", async () => {
        entries("topics", "7");
        window.fetch = vi.fn(async () => ({ ok: false, status: 404, text: async () => "" }));
        tabs.refusal = vi.fn(() => "Gone");
        window.CatapultChannel = { ...window.CatapultChannel, showError: vi.fn() };
        await click(".admin-dc-detail");

        expect(tabs.refusal).toHaveBeenCalledWith(expect.anything(), 404);
        expect(window.CatapultChannel.showError).toHaveBeenCalledWith("Gone");
        expect(document.getElementById("admin-dc-dialog").open).not.toBe(true);
    });

    it("patches the results when the entry is gone, and nothing else on another refusal", async () => {
        entries("topics", "7");
        sent = 404;
        await click(".admin-dc-refetch");
        expect(tabs.patch).toHaveBeenCalledTimes(1);

        sent = 502;
        await click(".admin-dc-refetch");
        expect(tabs.patch).toHaveBeenCalledTimes(1);
        expect(snackbars()).toEqual([]);
    });

    it("asks before starting the refetch, then follows it until it ends", async () => {
        summary("IDLE");
        jobState = "RUNNING";
        await click("#admin-dc-start");

        expect(tabs.confirmed).toHaveBeenCalledWith(expect.anything(), "Refetch all?", "Costs requests");
        expect(tabs.post.mock.calls[0][2]).toBe("/admin/dtdd-caches/refetch-all/start");
        expect(tabs.patch).toHaveBeenCalledTimes(1);

        await vi.advanceTimersByTimeAsync(POLL_MS);
        expect(tabs.patch).toHaveBeenCalledTimes(2);

        jobState = "DONE";
        await vi.advanceTimersByTimeAsync(POLL_MS);
        expect(tabs.patch).toHaveBeenCalledTimes(3);
        // Done: no more patches
        await vi.advanceTimersByTimeAsync(POLL_MS * 3);
        expect(tabs.patch).toHaveBeenCalledTimes(3);
    });

    it("starts nothing when the admin declines", async () => {
        summary("IDLE");
        confirmAnswer = false;
        await click("#admin-dc-start");

        expect(tabs.post).not.toHaveBeenCalled();
    });

    it("follows a refetch started elsewhere meanwhile (409)", async () => {
        summary("IDLE");
        sent = 409;
        jobState = "RUNNING";
        await click("#admin-dc-start");

        await vi.advanceTimersByTimeAsync(POLL_MS);
        expect(tabs.patch).toHaveBeenCalledTimes(2);
    });

    it("stops the refetch", async () => {
        summary("RUNNING");
        await click("#admin-dc-cancel");

        expect(tabs.post.mock.calls[0][2]).toBe("/admin/dtdd-caches/refetch-all/cancel");
        expect(tabs.patch).toHaveBeenCalledTimes(1);
    });

    it("follows a running refetch shown, only while its tab is the one shown", async () => {
        summary("RUNNING");
        document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "dtdd-caches", panel: panel() } }));

        await vi.advanceTimersByTimeAsync(POLL_MS);
        expect(tabs.patch).toHaveBeenCalledTimes(1);

        document.getElementById("admin-tabs").value = "members";
        await vi.advanceTimersByTimeAsync(POLL_MS * 3);
        expect(tabs.patch).toHaveBeenCalledTimes(1);

        // Back on it
        const tabsElement = document.getElementById("admin-tabs");
        tabsElement.value = "dtdd-caches";
        tabsElement.dispatchEvent(new Event("change", { bubbles: true }));
        await vi.advanceTimersByTimeAsync(POLL_MS);
        expect(tabs.patch).toHaveBeenCalledTimes(2);
    });
});
