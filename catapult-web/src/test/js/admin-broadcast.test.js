/*
 * The admin Broadcast tab: the select shows one type's form, each form posts its JSON, the outcome is
 * told by the status, and "Cancel it" prefills the cancellation of the announced maintenance.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let status;

const form = name => document.querySelector(`form[data-broadcast="${name}"]`);
const field = (name, key) => form(name).querySelector(`[name="${key}"]`);
const set = (name, key, value) => { field(name, key).value = value; field(name, key).dispatchEvent(new Event("input", { bubbles: true })); };
const send = async name => { form(name).querySelector(".admin-broadcast-send").click(); await flush(); };

function page(current = true) {
    html(`
        <mdui-tab-panel value="broadcast">
            <div class="admin-view admin-broadcast" data-label-sent="Sent" data-label-invalid="Refused"
                 data-label-rate="Slow down" data-label-unavailable="Down">
                ${current ? `<div data-current-starts-at="2026-10-20T20:00:00Z"><button id="admin-broadcast-cancel-current"></button></div>` : ""}
                <select name="type"><option value="maintenance.scheduled"></option><option value="maintenance.cancelled"></option>
                    <option value="version.deployed"></option><option value="alert.info"></option><option value="alert.warning"></option></select>
                <form data-broadcast="maintenance.scheduled">
                    <input name="startsAt"><input name="durationMinutes"><input name="message">
                    <button class="admin-broadcast-send" type="button"></button>
                </form>
                <form data-broadcast="maintenance.cancelled" hidden>
                    <input name="originalStartsAt"><input name="reason">
                    <button class="admin-broadcast-send" type="button"></button>
                </form>
                <form data-broadcast="version.deployed" hidden>
                    <input name="version"><input type="checkbox" name="promptReload">
                    <button class="admin-broadcast-send" type="button"></button>
                </form>
                <form data-broadcast="alert.info" hidden>
                    <input name="title"><input name="body"><input name="ttlSeconds" value="0">
                    <button class="admin-broadcast-send" type="button"></button>
                </form>
                <form data-broadcast="alert.warning" hidden>
                    <input name="title"><input name="body"><input name="ttlSeconds" value="0">
                    <button class="admin-broadcast-send" type="button"></button>
                </form>
            </div>
        </mdui-tab-panel>`);
    render();
}

beforeEach(async () => {
    status = 202;
    posted = vi.fn(async () => response(status));
    window.CatapultCsrf = { postJson: posted };
    window.CatapultChannel = { showError: vi.fn() };
    window.mdui = { snackbar: vi.fn() };
    window.CatapultAdminTabs = { reload: vi.fn(async () => {}) };
    await load("admin-broadcast");
    page();
});

describe("admin-broadcast", () => {
    it("shows only the form of the type picked", () => {
        const type = document.querySelector('[name="type"]');
        type.value = "alert.warning";
        type.dispatchEvent(new Event("change", { bubbles: true }));

        const shown = [...document.querySelectorAll("form[data-broadcast]")].filter(f => !f.hidden).map(f => f.dataset.broadcast);
        expect(shown).toEqual(["alert.warning"]);
    });

    it("posts a scheduled maintenance with its start as an ISO instant and its duration as a number", async () => {
        set("maintenance.scheduled", "startsAt", "2026-10-20T20:00");
        set("maintenance.scheduled", "durationMinutes", "30");
        set("maintenance.scheduled", "message", "m");
        await send("maintenance.scheduled");

        expect(posted).toHaveBeenCalledWith("/admin/broadcast", {
            name: "maintenance.scheduled", startsAt: new Date("2026-10-20T20:00").toISOString(), durationMinutes: 30, message: "m"
        });
    });

    it("posts a cancellation, a version (switch as boolean) and both alerts", async () => {
        set("maintenance.cancelled", "originalStartsAt", "2026-10-20T20:00");
        set("maintenance.cancelled", "reason", "done");
        await send("maintenance.cancelled");
        expect(posted).toHaveBeenLastCalledWith("/admin/broadcast", {
            name: "maintenance.cancelled", originalStartsAt: new Date("2026-10-20T20:00").toISOString(), reason: "done"
        });

        set("version.deployed", "version", "1.2.3");
        field("version.deployed", "promptReload").checked = true;
        await send("version.deployed");
        expect(posted).toHaveBeenLastCalledWith("/admin/broadcast", { name: "version.deployed", version: "1.2.3", promptReload: true });

        for (const name of ["alert.info", "alert.warning"]) {
            set(name, "title", "T");
            set(name, "body", "B");
            set(name, "ttlSeconds", "60");
            await send(name);
            expect(posted).toHaveBeenLastCalledWith("/admin/broadcast", { name, title: "T", body: "B", ttlSeconds: 60 });
        }
    });

    it("refuses missing or invalid fields without posting, marking them", async () => {
        set("maintenance.scheduled", "durationMinutes", "0");
        await send("maintenance.scheduled");

        expect(posted).not.toHaveBeenCalled();
        expect(field("maintenance.scheduled", "startsAt").hasAttribute("invalid")).toBe(true);
        expect(field("maintenance.scheduled", "durationMinutes").hasAttribute("invalid")).toBe(true);
        expect(field("maintenance.scheduled", "message").hasAttribute("invalid")).toBe(true);
    });

    it("tells a 202 with a snackbar and reloads the tab, so the form and the announced maintenance are fresh", async () => {
        set("alert.info", "title", "T");
        set("alert.info", "body", "B");
        await send("alert.info");

        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Sent" });
        expect(window.CatapultAdminTabs.reload).toHaveBeenCalledWith(document.querySelector('mdui-tab-panel[value="broadcast"]'));
    });

    it.each([[400, "Refused"], [429, "Slow down"], [502, "Down"]])("tells a %i", async (code, label) => {
        status = code;
        set("alert.info", "title", "T");
        set("alert.info", "body", "B");
        await send("alert.info");

        expect(window.CatapultChannel.showError).toHaveBeenCalledWith(label);
        expect(field("alert.info", "title").value).toBe("T");
        expect(window.CatapultAdminTabs.reload).not.toHaveBeenCalled();
    });

    it("tells an unreachable server", async () => {
        posted.mockRejectedValueOnce(new Error("network"));
        set("alert.info", "title", "T");
        set("alert.info", "body", "B");
        await send("alert.info");

        expect(window.CatapultChannel.showError).toHaveBeenCalledWith("Down");
    });

    it("cancels the announced maintenance with its exact start", async () => {
        document.getElementById("admin-broadcast-cancel-current").click();

        expect(form("maintenance.cancelled").hidden).toBe(false);
        expect(form("maintenance.scheduled").hidden).toBe(true);
        expect(document.querySelector('[name="type"]').value).toBe("maintenance.cancelled");
        expect(field("maintenance.cancelled", "originalStartsAt").value).not.toBe("");

        set("maintenance.cancelled", "reason", "done");
        await send("maintenance.cancelled");
        expect(posted).toHaveBeenCalledWith("/admin/broadcast", {
            name: "maintenance.cancelled", originalStartsAt: "2026-10-20T20:00:00Z", reason: "done"
        });
    });

    it("posts once on a double click", async () => {
        set("alert.info", "title", "T");
        set("alert.info", "body", "B");
        const button = form("alert.info").querySelector(".admin-broadcast-send");
        button.click();
        button.click();
        await flush();

        expect(posted).toHaveBeenCalledTimes(1);
    });
});
