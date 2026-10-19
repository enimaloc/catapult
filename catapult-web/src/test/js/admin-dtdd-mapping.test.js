/*
 * The admin DTDD mapping tab: the status chips load their list, Approve posts at once, Reject asks
 * an optional reason in a dialog first; 409 / 404 are told as such and reload the list in the
 * status being viewed.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let fetched;

const dialog = () => document.getElementById("admin-dtdd-dialog");
const reason = () => dialog().querySelector('[name="reason"]');

function page(status = "PENDING") {
    html(`
        <mdui-tab-panel value="dtdd-mapping" data-unavailable="Panel down">
            <div class="admin-view admin-dtdd-mapping" data-status="${status}" data-label-conflict="Already resolved"
                 data-label-unknown="Gone" data-label-unavailable="Down" data-label-approved="Approved" data-label-rejected="Rejected">
                <mdui-chip data-status-filter="PENDING"></mdui-chip>
                <mdui-chip data-status-filter="APPROVED"></mdui-chip>
                <button class="admin-dtdd-approve" data-approve="p/1"></button>
                <button class="admin-dtdd-reject" data-reject="p/1"></button>
                <div id="admin-dtdd-dialog">
                    <div class="admin-dtdd-form">
                        <input name="reason">
                        <button class="admin-dialog-close"></button>
                        <button class="admin-dtdd-confirm-reject"></button>
                    </div>
                </div>
            </div>
        </mdui-tab-panel>`);
    render();
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

beforeEach(async () => {
    posted = vi.fn(async () => response(204));
    fetched = vi.fn(async () => response(200, '<div class="admin-dtdd-mapping" data-status="APPROVED">fresh</div>'));
    window.fetch = fetched;
    window.CatapultCsrf = { postJson: posted };
    window.CatapultChannel = { showError: vi.fn() };
    window.mdui = { snackbar: vi.fn() };
    await load("admin-dtdd-mapping");
    page();
});

describe("admin-dtdd-mapping", () => {
    it("loads the list of the chip's status, and keeps it for the reloads", async () => {
        await click('[data-status-filter="APPROVED"]');

        expect(fetched).toHaveBeenCalledWith("/spa/admin/dtdd-mapping?status=APPROVED");
        expect(document.querySelector(".admin-dtdd-mapping").dataset.status).toBe("APPROVED");
    });

    it("does nothing for the status already shown", async () => {
        await click('[data-status-filter="PENDING"]');
        expect(fetched).not.toHaveBeenCalled();
    });

    it("approves at once, then reloads the status being viewed", async () => {
        await click("[data-approve]");

        expect(posted).toHaveBeenCalledWith("/admin/dtdd-mapping/proposals/p%2F1/approve", {});
        expect(fetched).toHaveBeenCalledWith("/spa/admin/dtdd-mapping?status=PENDING");
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Approved" });
    });

    it("asks for an optional reason before rejecting, then reloads the tab", async () => {
        await click("[data-reject]");
        expect(dialog().open).toBe(true);
        expect(posted).not.toHaveBeenCalled();

        reason().value = "  duplicate ";
        await click(".admin-dtdd-confirm-reject");

        expect(posted).toHaveBeenCalledWith("/admin/dtdd-mapping/proposals/p%2F1/reject", { reason: "duplicate" });
        expect(dialog()?.open ?? false).toBe(false);
        expect(fetched).toHaveBeenCalledWith("/spa/admin/dtdd-mapping?status=PENDING");
    });

    it("rejects without a reason as an empty body", async () => {
        await click("[data-reject]");
        await click(".admin-dtdd-confirm-reject");

        expect(posted).toHaveBeenCalledWith("/admin/dtdd-mapping/proposals/p%2F1/reject", {});
    });

    it("forgets the previous reason when the dialog is opened again", async () => {
        await click("[data-reject]");
        reason().value = "old";
        await click(".admin-dialog-close");
        await click("[data-reject]");

        expect(reason().value).toBe("");
    });

    it("tells 409 as 'already resolved' and reloads", async () => {
        posted.mockResolvedValue(response(409));

        await click("[data-approve]");

        expect(CatapultChannel.showError).toHaveBeenCalledWith("Already resolved");
        expect(fetched).toHaveBeenCalledWith("/spa/admin/dtdd-mapping?status=PENDING");
    });

    it("tells 404 as gone and reloads", async () => {
        posted.mockResolvedValue(response(404));

        await click("[data-approve]");

        expect(CatapultChannel.showError).toHaveBeenCalledWith("Gone");
        expect(fetched).toHaveBeenCalled();
    });

    it("keeps the dialog open and does not reload when the rejection could not be sent", async () => {
        posted.mockResolvedValue(response(502));
        await click("[data-reject]");
        await click(".admin-dtdd-confirm-reject");

        expect(CatapultChannel.showError).toHaveBeenCalledWith("Down");
        expect(dialog().open).toBe(true);
        expect(fetched).not.toHaveBeenCalled();
    });

    it("sends one request on a double click", async () => {
        let release;
        posted.mockImplementation(() => new Promise(resolve => { release = () => resolve(response(204)); }));
        const button = document.querySelector("[data-approve]");

        button.click();
        button.click();
        await flush();
        expect(posted).toHaveBeenCalledTimes(1);
        expect(button.disabled).toBe(true);

        release();
        await flush();
    });

    it("says so when the list can't be fetched, leaving the page as it is", async () => {
        fetched.mockRejectedValue(new Error("offline"));

        await click('[data-status-filter="APPROVED"]');

        expect(CatapultChannel.showError).toHaveBeenCalledWith("Down");
        expect(document.querySelector("[data-approve]")).not.toBeNull();
    });
});
