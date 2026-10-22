/*
 * The admin Notifications tab: the create dialog checks its fields as catapult-api does before
 * posting, previews the Markdown through the server, targets everyone or one member; a notification
 * is deleted after a confirmation. Both reload the tab.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let confirmAnswer;
let searched;
let found;

const dialog = () => document.getElementById("admin-notifications-dialog");
const field = name => dialog().querySelector(`[name="${name}"]`);
const save = () => dialog().querySelector(".admin-notifications-save");
const preview = () => dialog().querySelector(".notification-preview");

function page() {
    html(`
        <mdui-tab-panel value="notifications">
            <div class="admin-view admin-notifications" data-confirm-text="OK" data-cancel-text="No"
                 data-label-invalid="Refused" data-label-cta-pair="Both" data-label-past-expiry="Past"
                 data-label-member-required="Pick one" data-label-unknown-member="Gone" data-label-unavailable="Down" data-label-created="Sent">
                <button id="admin-notifications-new"></button>
                <div data-admin-results>
                    <div class="admin-notification-row" data-id="n1">
                        <button class="admin-notification-delete" data-delete="n1" data-confirm="Delete it?"></button>
                    </div>
                </div>
                <div id="admin-notifications-dialog">
                    <div class="admin-notifications-form">
                        <input name="title">
                        <div name="mode" value="edit"></div>
                        <textarea name="body"></textarea>
                        <div class="notification-preview" hidden></div>
                        <select name="severity"><option value="INFO">i</option><option value="WARNING">w</option></select>
                        <input name="ctaLabel"><input name="ctaUrl"><input name="expiresAt">
                        <div name="audience" value="ALL"></div>
                        <p class="admin-notifications-chat-commands" hidden></p>
                        <div class="admin-notifications-member" hidden>
                            <input name="memberSearch">
                            <div class="admin-notifications-members"></div>
                        </div>
                        <button class="admin-dialog-close"></button>
                        <button class="admin-notifications-save"></button>
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

function type(name, value) {
    field(name).value = value;
    field(name).dispatchEvent(new Event("input", { bubbles: true }));
}

function change(name, value) {
    field(name).value = value;
    field(name).dispatchEvent(new Event("change", { bubbles: true }));
}

/** Types in the member search and lets its debounce run out and the answer arrive. */
async function search(value) {
    vi.useFakeTimers();
    try {
        type("memberSearch", value);
        await vi.advanceTimersByTimeAsync(300);
    } finally {
        vi.useRealTimers();
    }
    await flush();
}

const options = () => dialog().querySelectorAll(".admin-notifications-member-option");

const valid = () => {
    type("title", "Maintenance");
    type("body", "**soon**");
};

beforeEach(async () => {
    history.replaceState(null, "", "/admin#notifications");
    posted = vi.fn(async url => (url.endsWith("/preview") ? response(200, "<p><strong>soon</strong></p>") : response(201)));
    confirmAnswer = true;
    found = [{ id: "m1", login: "alice" }];
    searched = vi.fn(async () => response(200, found));
    vi.stubGlobal("fetch", searched);
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())), snackbar: vi.fn() };
    await load("admin-tabs", "admin-notifications");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
    await click("#admin-notifications-new");
});

describe("admin-notifications", () => {
    it("opens the dialog", () => {
        expect(dialog().open).toBe(true);
    });

    it("refuses an empty title or body, marking the field", async () => {
        type("body", "x");
        await click(".admin-notifications-save");
        expect(posted).not.toHaveBeenCalled();
        expect(field("title").hasAttribute("invalid")).toBe(true);

        type("title", "t");
        expect(field("title").hasAttribute("invalid")).toBe(false);
        type("body", "  ");
        await click(".admin-notifications-save");
        expect(posted).not.toHaveBeenCalled();
        expect(field("body").hasAttribute("invalid")).toBe(true);
    });

    it("refuses a call-to-action label without a URL, and the reverse", async () => {
        valid();
        type("ctaLabel", "Go");
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Both");

        type("ctaLabel", "");
        type("ctaUrl", "https://x.org");
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenCalledTimes(2);
        expect(posted).not.toHaveBeenCalled();
    });

    it("refuses a call-to-action URL that is not http(s)", async () => {
        valid();
        type("ctaLabel", "Go");
        type("ctaUrl", "javascript:alert(1)");
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Refused");
        expect(posted).not.toHaveBeenCalled();
    });

    it("refuses an expiry in the past", async () => {
        valid();
        type("expiresAt", "2001-01-01T10:00");
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Past");
        expect(posted).not.toHaveBeenCalled();
    });

    it("posts a valid form, then reloads the tab", async () => {
        valid();
        await click(".admin-notifications-save");
        expect(posted.mock.calls).toEqual([["/admin/notifications", {
            title: "Maintenance", body: "**soon**", severity: "INFO",
            ctaUrl: null, ctaLabel: null, expiresAt: null, targetUserId: null, audience: null
        }]]);
        expect(dialog().open).toBe(false);
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("sends the call to action and the expiry as ISO", async () => {
        valid();
        change("severity", "WARNING");
        type("ctaLabel", "Read");
        type("ctaUrl", "https://x.org/a");
        const local = new Date(Date.now() + 86_400_000);
        const pad = n => String(n).padStart(2, "0");
        type("expiresAt", `${local.getFullYear()}-${pad(local.getMonth() + 1)}-${pad(local.getDate())}T${pad(local.getHours())}:${pad(local.getMinutes())}`);
        await click(".admin-notifications-save");
        const [, body] = posted.mock.calls[0];
        expect(body).toMatchObject({ severity: "WARNING", ctaLabel: "Read", ctaUrl: "https://x.org/a" });
        expect(body.expiresAt).toMatch(/Z$/);
        expect(Math.abs(Date.parse(body.expiresAt) - local.getTime())).toBeLessThan(60_000);
    });

    it("refuses 'one member' without a chosen member, and targets the chosen one", async () => {
        valid();
        change("audience", "ONE");
        expect(dialog().querySelector(".admin-notifications-member").hidden).toBe(false);
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Pick one");
        expect(posted).not.toHaveBeenCalled();

        await search("ali");
        expect(options()).toHaveLength(1);
        expect(options()[0].textContent).toBe("alice");
        options()[0].click();
        expect(options()).toHaveLength(0);
        expect(field("memberSearch").value).toBe("alice");
        await click(".admin-notifications-save");
        expect(posted.mock.calls[0][1].targetUserId).toBe("m1");
    });

    it("searches the members 300 ms after the last key, not the members of a page", async () => {
        change("audience", "ONE");
        vi.useFakeTimers();
        try {
            type("memberSearch", "a");
            await vi.advanceTimersByTimeAsync(200);
            type("memberSearch", "al&i");
            await vi.advanceTimersByTimeAsync(299);
            expect(searched).not.toHaveBeenCalled();
            await vi.advanceTimersByTimeAsync(1);
        } finally {
            vi.useRealTimers();
        }
        expect(searched).toHaveBeenCalledOnce();
        expect(searched).toHaveBeenCalledWith("/spa/admin/members/search?q=al%26i");
    });

    it("lists no option for a blank search, and drops an answer older than the last search", async () => {
        change("audience", "ONE");
        await search("ali");
        expect(options()).toHaveLength(1);
        await search("  ");
        expect(options()).toHaveLength(0);
        expect(searched).toHaveBeenCalledOnce();
    });

    it("says so when the search fails, keeping the options it had", async () => {
        change("audience", "ONE");
        await search("ali");
        searched.mockResolvedValue(response(503));
        await search("alic");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(options()).toHaveLength(1);
    });

    it("tells each streamer with chat commands, saying how", async () => {
        valid();
        change("audience", "CHAT_COMMANDS");
        expect(dialog().querySelector(".admin-notifications-chat-commands").hidden).toBe(false);
        expect(dialog().querySelector(".admin-notifications-member").hidden).toBe(true);
        await click(".admin-notifications-save");
        expect(posted.mock.calls[0][1]).toMatchObject({ audience: "CHAT_COMMANDS", targetUserId: null });
    });

    it("forgets the chosen member when the search is typed over", async () => {
        valid();
        change("audience", "ONE");
        await search("ali");
        dialog().querySelector('[data-member-id="m1"]').click();
        await search("alic");
        await click(".admin-notifications-save");
        expect(posted).not.toHaveBeenCalled();
    });

    it("keeps the dialog open and says why on a 400, a 404, then a 502", async () => {
        valid();
        posted.mockResolvedValueOnce(response(400));
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Refused");
        expect(dialog().open).toBe(true);
        expect(save().disabled).toBe(false);

        posted.mockResolvedValueOnce(response(404));
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Gone");

        posted.mockResolvedValueOnce(response(502));
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
    });

    it("says unavailable when the request could not be sent", async () => {
        valid();
        posted.mockRejectedValueOnce(new Error("offline"));
        await click(".admin-notifications-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(save().disabled).toBe(false);
    });

    it("posts once on a double click", async () => {
        valid();
        save().click();
        save().click();
        await flush();
        expect(posted).toHaveBeenCalledTimes(1);
    });

    it("previews the Markdown through the server, then goes back to the text", async () => {
        type("body", "**soon**");
        change("mode", "preview");
        await flush();
        expect(posted).toHaveBeenCalledWith("/admin/notifications/preview", { body: "**soon**" });
        expect(preview().innerHTML).toBe("<p><strong>soon</strong></p>");
        expect(preview().hidden).toBe(false);
        expect(field("body").hidden).toBe(true);

        change("mode", "edit");
        expect(preview().hidden).toBe(true);
        expect(field("body").hidden).toBe(false);
    });

    it("stays on the text when the preview fails", async () => {
        posted.mockResolvedValueOnce(response(502));
        type("body", "x");
        change("mode", "preview");
        await flush();
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(preview().hidden).toBe(true);
        expect(field("body").hidden).toBe(false);
        expect(field("mode").value).toBe("edit");
    });

    it("deletes after a confirmation, the button disabled meanwhile, then reloads", async () => {
        let release;
        posted.mockImplementationOnce(() => new Promise(resolve => { release = () => resolve(response(204)); }));
        const button = document.querySelector(".admin-notification-delete");
        button.click();
        await flush();
        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Delete it?" }));
        expect(button.disabled).toBe(true);
        expect(posted).toHaveBeenCalledWith("/admin/notifications/n1/delete", {});
        release();
        await flush();
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("opens one confirmation and posts once on a double click", async () => {
        const button = document.querySelector(".admin-notification-delete");
        button.click();
        button.click();
        await flush();
        expect(mdui.confirm).toHaveBeenCalledTimes(1);
        expect(posted).toHaveBeenCalledTimes(1);
    });

    it("re-enables the delete button when the confirmation is declined", async () => {
        confirmAnswer = false;
        await click(".admin-notification-delete");
        expect(document.querySelector(".admin-notification-delete").disabled).toBe(false);
    });

    it("does not delete when the confirmation is declined", async () => {
        confirmAnswer = false;
        await click(".admin-notification-delete");
        expect(posted).not.toHaveBeenCalled();
    });

    it("re-enables the delete button when it fails", async () => {
        posted.mockResolvedValueOnce(response(502));
        await click(".admin-notification-delete");
        expect(document.querySelector(".admin-notification-delete").disabled).toBe(false);
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
    });

    it("opens the dialog empty each time", async () => {
        valid();
        dialog().open = false;
        await click("#admin-notifications-new");
        expect(field("title").value).toBe("");
        expect(field("body").value).toBe("");
        expect(field("audience").value).toBe("ALL");
    });
});
