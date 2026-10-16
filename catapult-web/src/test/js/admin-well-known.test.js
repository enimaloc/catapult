/*
 * The admin Well-known tab: a file's dialog (new, from the registry, or an existing one), its
 * Content-Type following the path until edited, a file imported as text, Save / Delete; the
 * registry filtered as typed.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let fetched;
let posted;
let confirmAnswer;

const dialog = () => document.getElementById("admin-well-known-dialog");
const field = name => dialog().querySelector(`[name="${name}"]`);
const fetchedUrls = () => fetched.mock.calls.map(([url]) => url);

function form(id) {
    return `<div class="admin-well-known-form" ${id ? `data-id="${id}"` : ""}>
        <select name="location"><option value="WELL_KNOWN">w</option><option value="ROOT">r</option></select>
        <input name="path">
        <code class="admin-well-known-url"></code>
        <input name="contentType" ${id ? 'data-manual="true"' : ""}>
        <textarea name="content"></textarea>
        <input type="file" class="admin-well-known-import">
        <button class="admin-well-known-import-button"></button>
        <button class="admin-dialog-close"></button>
        <button class="admin-well-known-save"></button>
    </div>`;
}

function page() {
    html(`
        <mdui-tabs id="admin-tabs"></mdui-tabs>
        <mdui-tab-panel value="well-known">
            <div class="admin-view admin-well-known" data-confirm-text="OK" data-cancel-text="No"
                 data-error="Failed ({0})" data-error-400="Invalid" data-error-404="Gone" data-error-409="Taken"
                 data-error-too-large="Too large" data-error-binary="Not text">
                <button id="admin-well-known-new"></button>
                <div class="admin-well-known-file" data-id="4">
                    <button class="admin-well-known-edit"></button>
                    <button class="admin-well-known-delete" data-confirm="Delete it?"></button>
                </div>
                <input data-well-known-filter>
                <div class="admin-iana-row" data-search="security.txt rfc 9116">
                    <button class="admin-well-known-configure" data-path="security.txt"></button></div>
                <div class="admin-iana-row" data-search="acme-challenge rfc 8555"></div>
                <div id="admin-well-known-dialog"><div class="admin-dialog-body"></div></div>
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

function importFile(file) {
    const input = dialog().querySelector(".admin-well-known-import");
    Object.defineProperty(input, "files", { value: [file], configurable: true });
    input.dispatchEvent(new Event("change", { bubbles: true }));
}

beforeEach(async () => {
    history.replaceState(null, "", "/admin#well-known");
    fetched = vi.fn(async url => response(200, url.includes("?id=") ? form(4) : form(null)));
    posted = vi.fn(async () => response(201));
    confirmAnswer = true;
    window.fetch = fetched;
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())) };
    await load("admin-tabs", "admin-well-known");
    window.CatapultChannel = { showError: vi.fn() }; // after load: channel-page.js isn't loaded, nothing overrides it
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
});

describe("admin-well-known", () => {
    it("derives the Content-Type from the extension", () => {
        const { contentType } = window.CatapultAdminWellKnown;
        expect(contentType("assetlinks.json")).toBe("application/json");
        expect(contentType("a/b.XML")).toBe("application/xml");
        expect(contentType("google1.html")).toBe("text/html; charset=utf-8");
        expect(contentType("acme-challenge/abc")).toBe("text/plain; charset=utf-8");
    });

    it("opens a registry suffix's dialog with its path", async () => {
        page();
        await click(".admin-well-known-configure");
        expect(fetchedUrls()).toEqual(["/spa/admin/well-known/file?location=WELL_KNOWN&path=security.txt"]);
        expect(dialog().open).toBe(true);
    });

    it("follows the path with the type and the URL preview until the type is edited", async () => {
        page();
        await click("#admin-well-known-new");
        type("path", "assetlinks.json");
        expect(field("contentType").value).toBe("application/json");
        expect(dialog().querySelector(".admin-well-known-url").textContent).toBe("/.well-known/assetlinks.json");

        field("location").value = "ROOT";
        field("location").dispatchEvent(new Event("change", { bubbles: true }));
        expect(dialog().querySelector(".admin-well-known-url").textContent).toBe("/assetlinks.json");

        type("contentType", "text/plain");
        type("path", "x.xml");
        expect(field("contentType").value).toBe("text/plain");
    });

    it("keeps an existing file's type when its path changes", async () => {
        page();
        await click(".admin-well-known-edit");
        expect(fetchedUrls()).toEqual(["/spa/admin/well-known/file?id=4"]);
        field("contentType").value = "text/plain";
        type("path", "x.json");
        expect(field("contentType").value).toBe("text/plain");
    });

    it("imports a text file into the content", async () => {
        page();
        await click("#admin-well-known-new");
        importFile(new File(["Contact: mailto:a@b.c\n"], "security.txt", { type: "text/plain" }));
        await vi.waitFor(() => expect(field("content").value).toBe("Contact: mailto:a@b.c\n"));
    });

    it("refuses a binary or too large file, leaving the content as it was", async () => {
        page();
        await click("#admin-well-known-new");
        field("content").value = "kept";

        importFile(new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x00, 0x01])], "a.png"));
        await vi.waitFor(() => expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Not text"));
        expect(field("content").value).toBe("kept");

        importFile(new File(["x".repeat(65_537)], "big.txt"));
        await vi.waitFor(() => expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Too large"));
        expect(field("content").value).toBe("kept");
    });

    it("creates a file then reloads the tab", async () => {
        page();
        await click("#admin-well-known-new");
        type("path", "riot.txt");
        field("location").value = "ROOT";
        field("content").value = "code";
        await click(".admin-well-known-save");

        expect(posted.mock.calls).toEqual([["/admin/well-known",
            { location: "ROOT", path: "riot.txt", contentType: "text/plain; charset=utf-8", content: "code" }]]);
        expect(dialog().open).toBe(false);
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("updates an existing file at its own route", async () => {
        page();
        await click(".admin-well-known-edit");
        posted.mockResolvedValue(response(200));
        await click(".admin-well-known-save");
        expect(posted.mock.calls[0][0]).toBe("/admin/well-known/4");
    });

    it("says why a save was refused and keeps the dialog open, its Save usable again", async () => {
        posted.mockResolvedValue(response(409));
        page();
        await click("#admin-well-known-new");
        await click(".admin-well-known-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Taken");
        expect(dialog().open).toBe(true);
        expect(dialog().querySelector(".admin-well-known-save").disabled).toBe(false);

        posted.mockResolvedValue(response(500));
        await click(".admin-well-known-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Failed (500)");

        posted.mockRejectedValue(new Error("offline"));
        await click(".admin-well-known-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Failed (—)");
    });

    it("deletes a file once confirmed, not otherwise", async () => {
        page();
        confirmAnswer = false;
        await click(".admin-well-known-delete");
        expect(posted).not.toHaveBeenCalled();

        confirmAnswer = true;
        posted.mockResolvedValue(response(204));
        await click(".admin-well-known-delete");
        expect(posted.mock.calls).toEqual([["/admin/well-known/4/delete", {}]]);
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("filters the registry as typed", async () => {
        page();
        const filter = document.querySelector("[data-well-known-filter]");
        filter.value = "ACME";
        filter.dispatchEvent(new Event("input", { bubbles: true }));
        const rows = [...document.querySelectorAll(".admin-iana-row")];
        expect(rows.map(r => r.hidden)).toEqual([true, false]);
    });

    it("reports an opened file that no longer exists", async () => {
        fetched.mockResolvedValueOnce(response(404, "<p>gone</p>"));
        page();
        await click(".admin-well-known-edit");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Gone");
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });
});
