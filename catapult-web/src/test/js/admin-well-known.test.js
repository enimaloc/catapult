/*
 * The admin Well-known tab: a file's dialog (new, from the registry, or an existing one), its
 * Content-Type following the path until edited, a file imported as text, Save / Delete; the
 * registry filtered as typed; the Response tab's status and headers, the content edited through a form
 * (dedicated or generated from a schema) or as raw text.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let fetched;
let posted;
let confirmAnswer;

const dialog = () => document.getElementById("admin-well-known-dialog");
const field = name => dialog().querySelector(`[name="${name}"]`);
const fetchedUrls = () => fetched.mock.calls.map(([url]) => url);

let served; // the file the dialog fragment shows: { path, location, content, status, headers }
let schemas; // path → the schema served by /spa/admin/well-known/schema

const esc = text => String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");

function form(id) {
    const { location = "WELL_KNOWN", path = "", content = "", status = "200", headers = [] } = served;
    const known = ["200", "204", "301", "302", "307", "308", "404", "410"].includes(String(status));
    return `<div class="admin-well-known-form" ${id ? `data-id="${id}"` : ""} data-label-add="Add" data-label-remove="Remove"
                 data-label-invalid-json="No form: {0}" data-label-schema-warnings="Schema: {0}. Save?"
                 data-label-expires-far="Too far" data-label-header-name="Header" data-label-header-value="Value"
                 data-label-header-remove="Remove this header">
        <select name="location"><option value="WELL_KNOWN">w</option>
            <option value="ROOT" ${location === "ROOT" ? "selected" : ""}>r</option></select>
        <input name="path" value="${esc(path)}">
        <code class="admin-well-known-url"></code>
        <div class="admin-well-known-sections"></div>
        <select name="status">${["200", "204", "301", "302", "307", "308", "404", "410", "other"]
            .map(s => `<option value="${s}" ${(known ? String(status) : "other") === s ? "selected" : ""}>${s}</option>`).join("")}</select>
        <input name="statusOther" value="${known ? "" : status}" ${known ? "hidden" : ""}>
        <input name="contentType" ${id ? 'data-manual="true"' : ""}>
        <div class="admin-well-known-headers">${headers.map(h => `<div class="admin-well-known-header">
            <input data-header="name" value="${esc(h.name)}"><input data-header="value" value="${esc(h.value)}">
            <button class="admin-well-known-header-remove"></button></div>`).join("")}</div>
        <button class="admin-well-known-header-add"></button>
        <button class="admin-well-known-redirect"></button>
        <div class="admin-well-known-mode"><button value="form"></button><button value="raw"></button></div>
        <small class="admin-well-known-mode-reason"></small>
        <div class="admin-well-known-fields" hidden></div>
        <div class="admin-well-known-raw">
            <textarea name="content">${esc(content)}</textarea>
            <input type="file" class="admin-well-known-import">
            <button class="admin-well-known-import-button"></button>
        </div>
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
    served = {};
    schemas = {};
    fetched = vi.fn(async url => {
        if (url.startsWith("/spa/admin/well-known/schema?path=")) {
            const schema = schemas[decodeURIComponent(url.split("=")[1])];
            return schema ? response(200, schema) : response(404);
        }
        return response(200, url.includes("?id=") ? form(4) : form(null));
    });
    posted = vi.fn(async () => response(201));
    confirmAnswer = true;
    window.fetch = fetched;
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())) };
    await load("admin-tabs", "well-known-forms", "admin-well-known");
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
        expect(fetchedUrls()[0]).toBe("/spa/admin/well-known/file?location=WELL_KNOWN&path=security.txt");
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
        expect(fetchedUrls()[0]).toBe("/spa/admin/well-known/file?id=4");
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
            { location: "ROOT", path: "riot.txt", status: 200, headers: [], contentType: "text/plain; charset=utf-8", content: "code" }]]);
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

    it("reloads the tab when a save or a delete finds the file gone", async () => {
        page();
        await click(".admin-well-known-edit");
        posted.mockResolvedValue(response(404));
        await click(".admin-well-known-save");
        await vi.waitFor(() => expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1));
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Gone");

        await click(".admin-well-known-delete");
        await vi.waitFor(() => expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(2));
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Gone");
    });
});

describe("admin-well-known response and forms", () => {
    const mode = () => dialog().querySelector(".admin-well-known-mode");
    const formSegment = () => mode().querySelector('[value="form"]');
    const fields = () => dialog().querySelector(".admin-well-known-fields");
    const raw = () => dialog().querySelector(".admin-well-known-raw");
    const reason = () => dialog().querySelector(".admin-well-known-mode-reason").textContent;
    const items = key => [...fields().querySelectorAll(`fieldset[data-field="${key}"] > .admin-well-known-item [data-item]`)];
    const savedBody = () => posted.mock.calls.at(-1)[1];

    /** Saves; Save is re-enabled first, as a successful save leaves it disabled (the dialog closes then). */
    async function save() {
        dialog().querySelector(".admin-well-known-save").disabled = false;
        await click(".admin-well-known-save");
    }

    function switchMode(to) {
        mode().value = to;
        mode().dispatchEvent(new Event("change", { bubbles: true }));
    }

    async function open(file) {
        served = { location: "WELL_KNOWN", ...file };
        page();
        await click(".admin-well-known-edit");
    }

    const SECURITY = "Contact: mailto:a@b.c\nContact: https://b.c/sec\nExpires: 2027-01-01T00:00:00.000Z\n";

    it("opens security.txt as a form with its Contact rows; raw gets the serialized text", async () => {
        await open({ path: "security.txt", content: SECURITY });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        expect(fields().hidden).toBe(false);
        expect(raw().hidden).toBe(true);
        expect(items("Contact").map(i => i.value)).toEqual(["mailto:a@b.c", "https://b.c/sec"]);

        items("Contact")[0].value = "mailto:new@b.c";
        switchMode("raw");
        expect(raw().hidden).toBe(false);
        expect(fields().hidden).toBe(true);
        expect(field("content").value).toBe("Contact: mailto:new@b.c\nContact: https://b.c/sec\nExpires: 2027-01-01T00:00:00.000Z\n");
    });

    it("adds and removes list rows", async () => {
        await open({ path: "security.txt", content: SECURITY });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        fields().querySelector('fieldset[data-field="Contact"] > .admin-well-known-item-add').click();
        expect(items("Contact")).toHaveLength(3);
        items("Contact")[2].value = "mailto:c@b.c";
        items("Contact")[0].closest(".admin-well-known-item").querySelector(".admin-well-known-item-remove").click();
        switchMode("raw");
        expect(field("content").value).toContain("Contact: https://b.c/sec\nContact: mailto:c@b.c\n");
        expect(field("content").value).not.toContain("a@b.c");
    });

    it("falls back to raw with the reason on unreadable JSON, offering the form again once edited", async () => {
        await open({ path: "assetlinks.json", content: "{oops" });
        await vi.waitFor(() => expect(formSegment().disabled).toBe(true));
        expect(mode().value).toBe("raw");
        expect(raw().hidden).toBe(false);
        expect(reason()).toMatch(/^No form: .*JSON/);

        type("content", "[]");
        expect(formSegment().disabled).toBe(false);
        expect(reason()).toBe("");
        switchMode("form");
        expect(mode().value).toBe("form");
        expect(fields().querySelector('fieldset[data-field="apps"]')).not.toBeNull();
    });

    it("goes back to raw when switching to the form fails", async () => {
        await open({ path: "assetlinks.json", content: "[]" });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        switchMode("raw");
        field("content").value = "{";
        switchMode("form");
        expect(mode().value).toBe("raw");
        expect(formSegment().disabled).toBe(true);
        expect(reason()).toMatch(/^No form: /);
    });

    it("generates a form from the route's schema, hides the Form segment without one", async () => {
        schemas["gpc.json"] = { type: "object", required: ["gpc"], properties: { gpc: { type: "boolean" }, lastUpdate: { type: "string" } } };
        await open({ path: "gpc.json", content: '{"gpc": true}' });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        expect(fetchedUrls()).toContain("/spa/admin/well-known/schema?path=gpc.json");
        const box = fields().querySelector('mdui-checkbox[data-field="gpc"]');
        expect(box.checked).toBe(true);
        box.checked = false;
        await save();
        expect(JSON.parse(savedBody().content)).toEqual({ gpc: false });

        await open({ path: "unknown.json", content: "{}" });
        await vi.waitFor(() => expect(formSegment().hidden).toBe(true));
        expect(mode().value).toBe("raw");
        expect(fetchedUrls()).toContain("/spa/admin/well-known/schema?path=unknown.json");
    });

    it("asks no schema for a root file", async () => {
        await open({ path: "gpc.json", location: "ROOT", content: "{}" });
        await vi.waitFor(() => expect(formSegment().hidden).toBe(true));
        expect(fetchedUrls().some(u => u.includes("/schema"))).toBe(false);
    });

    it("turns the response into a redirect", async () => {
        await open({ path: "change-password", headers: [{ name: "Cache-Control", value: "no-store" }] });
        const focus = vi.spyOn(HTMLElement.prototype, "focus"); // jsdom can't focus an unknown element: spied
        await click(".admin-well-known-redirect");
        expect(field("status").value).toBe("302");
        expect(dialog().querySelector(".admin-well-known-sections").value).toBe("response");
        const rows = [...dialog().querySelectorAll(".admin-well-known-header")];
        expect(rows.map(r => r.querySelector('[data-header="name"]').value)).toEqual(["Cache-Control", "Location"]);
        expect(focus.mock.contexts).toEqual([rows[1].querySelector('[data-header="value"]')]);
        focus.mockRestore();

        await click(".admin-well-known-redirect");
        expect(dialog().querySelectorAll(".admin-well-known-header")).toHaveLength(2);
    });

    it("keeps an existing Location header on redirect, whatever its case", async () => {
        await open({ path: "x", headers: [{ name: "location", value: "/y" }] });
        await click(".admin-well-known-redirect");
        expect(dialog().querySelectorAll(".admin-well-known-header")).toHaveLength(1);
    });

    it("adds and removes header rows, saving the status and the named headers", async () => {
        await open({ path: "x", headers: [{ name: "X-A", value: "1" }, { name: "X-B", value: "2" }] });
        await click(".admin-well-known-header-add");
        await click(".admin-well-known-header-add");
        const rows = () => [...dialog().querySelectorAll(".admin-well-known-header")];
        expect(rows()).toHaveLength(4);
        rows()[2].querySelector('[data-header="name"]').value = "X-C";
        rows()[2].querySelector('[data-header="value"]').value = "3";
        rows()[0].querySelector(".admin-well-known-header-remove").click();
        expect(rows()).toHaveLength(3);
        await save();
        expect(savedBody().status).toBe(200);
        expect(savedBody().headers).toEqual([{ name: "X-B", value: "2" }, { name: "X-C", value: "3" }]);
    });

    it("shows the other status field and saves its number", async () => {
        await open({ path: "x" });
        field("status").value = "other";
        field("status").dispatchEvent(new Event("change", { bubbles: true }));
        expect(field("statusOther").hidden).toBe(false);
        field("statusOther").value = "418";
        await save();
        expect(savedBody().status).toBe(418);

        field("status").value = "404";
        field("status").dispatchEvent(new Event("change", { bubbles: true }));
        expect(field("statusOther").hidden).toBe(true);
        await save();
        expect(savedBody().status).toBe(404);
    });

    it("opens an uncommon status as other", async () => {
        await open({ path: "x", status: "418" });
        await save();
        expect(savedBody().status).toBe(418);
    });

    it("serializes the form before saving", async () => {
        await open({ path: "microsoft-identity-association.json", content: '{"associatedApplications":[{"applicationId":"a"}],"x":1}' });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        items("applicationIds")[0].value = "b";
        await save();
        expect(JSON.parse(savedBody().content)).toEqual({ associatedApplications: [{ applicationId: "b" }], x: 1 });
    });

    it("keeps an apple detail the form doesn't show in its slot when another is edited or removed", async () => {
        const A = { appIDs: ["A"], components: [{ "/": "/a" }] };
        const mixed = { appIDs: ["M"], components: [{ "#": "c" }] };
        const B = { appIDs: ["B"], components: [{ "/": "/b" }] };
        await open({ path: "apple-app-site-association", content: JSON.stringify({ applinks: { details: [A, mixed, B] } }) });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        const details = () => [...fields().querySelectorAll('fieldset[data-field="details"] > .admin-well-known-group-item')];
        expect(details()).toHaveLength(2);
        details()[1].querySelector('fieldset[data-field="paths"] [data-item]').value = "/b2";
        await save();
        expect(JSON.parse(savedBody().content).applinks.details).toEqual([A, mixed, { appIDs: ["B"], components: [{ "/": "/b2" }] }]);

        details()[0].querySelector(":scope > .admin-well-known-item-remove").click();
        await save();
        expect(JSON.parse(savedBody().content).applinks.details).toEqual([mixed, { appIDs: ["B"], components: [{ "/": "/b2" }] }]);
    });

    it("adds a group entry", async () => {
        await open({ path: "assetlinks.json", content: "[]" });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        fields().querySelector('fieldset[data-field="apps"] > .admin-well-known-item-add').click();
        const item = fields().querySelector(".admin-well-known-group-item");
        item.querySelector('[data-field="package_name"]').value = "com.x";
        item.querySelector('fieldset[data-field="sha256_cert_fingerprints"] > .admin-well-known-item-add').click();
        item.querySelector("[data-item]").value = "AB:CD";
        await save();
        expect(JSON.parse(savedBody().content)).toEqual([{ relation: ["delegate_permission/common.handle_all_urls"],
            target: { namespace: "android_app", package_name: "com.x", sha256_cert_fingerprints: ["AB:CD"] } }]);
    });

    it("asks before saving content that breaks the schema", async () => {
        schemas["gpc.json"] = { type: "object", required: ["gpc"], properties: { gpc: { type: "boolean" } } };
        await open({ path: "gpc.json", content: '{"gpc": "yes"}' });
        await vi.waitFor(() => expect(formSegment().disabled).toBe(true));
        confirmAnswer = false;
        await save();
        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Schema: gpc: boolean expected. Save?" }));
        expect(posted).not.toHaveBeenCalled();

        confirmAnswer = true;
        await save();
        await vi.waitFor(() => expect(posted).toHaveBeenCalledTimes(1));
    });

    it("lists five schema problems at most", async () => {
        schemas["many.json"] = { type: "object", required: ["a", "b", "c", "d", "e", "f", "g"], properties: { a: { type: "object" } } };
        await open({ path: "many.json", content: "{}" });
        await vi.waitFor(() => expect(fetchedUrls()).toContain("/spa/admin/well-known/schema?path=many.json"));
        await flush();
        confirmAnswer = false;
        await save();
        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({
            headline: "Schema: a: required, b: required, c: required, d: required, e: required, … (+2). Save?"
        }));
    });

    it("warns when security.txt expires more than a year ahead", async () => {
        await open({ path: "security.txt", content: `Contact: mailto:a@b.c\nExpires: ${new Date(Date.now() + 30 * 864e5).toISOString()}\n` });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        const expires = fields().querySelector('[data-field="Expires"]');
        expect(expires.getAttribute("type")).toBe("datetime-local");
        expect(expires.getAttribute("helper")).not.toBe("Too far");

        const far = new Date(Date.now() + 730 * 864e5);
        const pad = n => String(n).padStart(2, "0");
        expires.value = `${far.getFullYear()}-${pad(far.getMonth() + 1)}-${pad(far.getDate())}T${pad(far.getHours())}:${pad(far.getMinutes())}`;
        expires.dispatchEvent(new Event("input", { bubbles: true }));
        expect(expires.getAttribute("helper")).toBe("Too far");
    });

    it("switches to raw before importing in form mode", async () => {
        await open({ path: "security.txt", content: SECURITY });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        importFile(new File(["Contact: mailto:z@b.c\n"], "security.txt", { type: "text/plain" }));
        await vi.waitFor(() => expect(field("content").value).toBe("Contact: mailto:z@b.c\n"));
        expect(mode().value).toBe("raw");
        expect(raw().hidden).toBe(false);
    });

    it("labels a new header row from the form's labels", async () => {
        await open({ path: "change-password" });
        await click(".admin-well-known-redirect");
        const row = dialog().querySelector(".admin-well-known-header");
        expect(row.querySelector('[data-header="name"]').getAttribute("label")).toBe("Header");
        expect(row.querySelector('[data-header="value"]').getAttribute("label")).toBe("Value");
        expect(row.querySelector(".admin-well-known-header-remove").getAttribute("aria-label")).toBe("Remove this header");
    });

    it("offers the form once a known path is typed in a new file, staying raw", async () => {
        page();
        await click("#admin-well-known-new");
        await vi.waitFor(() => expect(formSegment().hidden).toBe(true));
        field("content").value = "Contact: mailto:a@b.c\n";
        type("path", "security.txt");
        expect(formSegment().hidden).toBe(false);
        expect(formSegment().disabled).toBe(false);
        expect(mode().value).toBe("raw");
        expect(field("content").value).toBe("Contact: mailto:a@b.c\n");
        switchMode("form");
        expect(items("Contact").map(i => i.value)).toEqual(["mailto:a@b.c"]);

        field("location").value = "ROOT";
        field("location").dispatchEvent(new Event("change", { bubbles: true }));
        expect(formSegment().hidden).toBe(true);
        expect(mode().value).toBe("raw");
        expect(field("content").value).toBe("Contact: mailto:a@b.c\n");
    });

    it("fetches the typed path's schema once typing pauses", async () => {
        schemas["gpc.json"] = { type: "object", properties: { gpc: { type: "boolean" } } };
        page();
        await click("#admin-well-known-new");
        type("path", "g");
        type("path", "gpc");
        type("path", "gpc.json");
        await vi.waitFor(() => expect(formSegment().hidden).toBe(false));
        expect(fetchedUrls().filter(u => u.includes("/schema"))).toEqual(["/spa/admin/well-known/schema?path=gpc.json"]);
        expect(mode().value).toBe("raw");
    });

    it("ignores the schema answer of a path typed over", async () => {
        let answerOld;
        const base = fetched.getMockImplementation();
        fetched.mockImplementation(url => (url.endsWith("path=old.json")
            ? new Promise(resolve => { answerOld = resolve; })
            : base(url)));
        page();
        await click("#admin-well-known-new");
        type("path", "old.json");
        await vi.waitFor(() => expect(answerOld).toBeDefined());
        type("path", "new.json");
        await vi.waitFor(() => expect(fetchedUrls()).toContain("/spa/admin/well-known/schema?path=new.json"));
        await flush();
        answerOld(response(200, { type: "object", properties: { gpc: { type: "boolean" } } }));
        await flush();
        expect(formSegment().hidden).toBe(true);
    });

    it("keeps the form's content, now raw, when the path changes", async () => {
        await open({ path: "security.txt", content: SECURITY });
        await vi.waitFor(() => expect(mode().value).toBe("form"));
        items("Contact")[0].value = "mailto:typed@b.c";
        type("path", "other.txt");
        expect(mode().value).toBe("raw");
        expect(raw().hidden).toBe(false);
        expect(formSegment().hidden).toBe(true);
        expect(field("content").value).toContain("Contact: mailto:typed@b.c\n");
        await click(".admin-well-known-save");
        expect(savedBody().content).toContain("Contact: mailto:typed@b.c\n");
    });
});
