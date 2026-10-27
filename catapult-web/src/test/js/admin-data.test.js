/*
 * The admin data tab's rows: a row (or "New") opens its dialog, Save creates or updates it,
 * Delete removes it once confirmed; a refusal says why and leaves the dialog open.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

const ID = "a~b%20c";
let fetched;
let posted;
let confirmAnswer;

const fetchedUrls = () => fetched.mock.calls.map(([url]) => url);
const dialog = () => document.getElementById("admin-data-dialog");

/**
 * The dialog body the server would render for `id` (none: a new row), each field with the value
 * it was rendered with as data-initial (jsdom has no mdui-text-field: its value is set by hand).
 */
function form(id) {
    // th:attr drops a blank data-initial, as it does a blank value.
    const field = (name, value) => (id && value
        ? `<mdui-text-field name="${name}" data-initial="${value}"></mdui-text-field>`
        : `<mdui-text-field name="${name}"></mdui-text-field>`);
    return `<div class="admin-data-form" ${id ? `data-id="${id}"` : ""}>
        ${id ? "" : '<mdui-text-field name="id"></mdui-text-field>'}
        ${field("name", "!lurk")}
        ${field("template", "hello")}
        ${field("cooldown", "")}
        ${id ? '<button class="admin-data-delete" data-confirm="Delete it?"></button>' : ""}
        <button class="admin-dialog-close"></button>
        <button class="admin-data-save"></button>
    </div>`;
}

function page() {
    html(`
        <mdui-tabs id="admin-tabs"></mdui-tabs>
        <mdui-tab-panel value="data">
            <div class="admin-view" data-repo="chat-command" data-confirm-text="OK" data-cancel-text="No"
                 data-error="Failed ({0})" data-error-400="Invalid" data-error-409="Still referenced">
                <button id="admin-data-new"></button>
                <div data-admin-results><div class="admin-data-row" data-id="${ID}"><span class="cell">x</span></div></div>
                <div id="admin-data-dialog"><div class="admin-dialog-body"></div></div>
            </div>
        </mdui-tab-panel>`);
    render();
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

function fill(name, value) {
    dialog().querySelector(`[name="${name}"]`).value = value;
}

/** Gives an opened row's fields their rendered values, as mdui-text-field does from its value attribute. */
function rendered() {
    dialog().querySelectorAll("[data-initial]").forEach(field => { field.value = field.dataset.initial; });
}

beforeEach(async () => {
    history.replaceState(null, "", "/admin#data/chat-command");
    fetched = vi.fn(async url => response(200, url.includes("/row?") ? form(ID) : url.includes("/row") ? form(null) : "<div data-admin-results></div>"));
    posted = vi.fn(async () => response(200));
    confirmAnswer = true;
    window.fetch = fetched;
    window.CatapultCsrf = { postJson: posted };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())) };
    await load("channel-page", "admin-tabs", "admin-data");
});

describe("admin-data", () => {
    it("opens a row's dialog, then saves the fields changed, then reloads the results", async () => {
        page();
        await click(".admin-data-row .cell");
        expect(fetchedUrls()).toEqual([`/spa/admin/data/chat-command/row?id=${encodeURIComponent(ID)}`]);
        expect(dialog().open).toBe(true);
        rendered();

        fill("name", "!lurker");
        fill("template", "");
        await click(".admin-data-save");

        // cooldown, blank (null) and left as it was, isn't sent: catapult-api can't read a number from "".
        expect(posted.mock.calls).toEqual([[`/admin/data/chat-command/update?id=${encodeURIComponent(ID)}`,
            { name: "!lurker", template: "" }]]);
        expect(dialog().open).toBe(false);
        expect(fetchedUrls().at(-1)).toBe("/spa/admin/data/chat-command?q=&page=0");
    });

    it("sends no field left as it was", async () => {
        page();
        await click(".admin-data-row");
        rendered();
        fill("name", "!lurk");
        await click(".admin-data-save");

        expect(posted.mock.calls).toEqual([[`/admin/data/chat-command/update?id=${encodeURIComponent(ID)}`, {}]]);
    });

    it("sends a blank field once given a value", async () => {
        page();
        await click(".admin-data-row");
        rendered();
        fill("cooldown", "30");
        await click(".admin-data-save");

        expect(posted.mock.calls).toEqual([[`/admin/data/chat-command/update?id=${encodeURIComponent(ID)}`, { cooldown: "30" }]]);
    });

    it("creates a row once however fast Save is clicked, and lets it be tried again after a failure", async () => {
        let answer;
        posted.mockImplementationOnce(() => new Promise(resolve => { answer = resolve; }));
        page();
        await click("#admin-data-new");
        fill("name", "!hi");
        const save = dialog().querySelector(".admin-data-save");

        save.click();
        save.click();
        await flush();
        expect(posted).toHaveBeenCalledTimes(1);
        expect(save.disabled).toBe(true);

        answer(response(400));
        await flush();
        expect(save.disabled).toBe(false);

        posted.mockResolvedValue(response(201));
        await click(".admin-data-save");
        expect(posted).toHaveBeenCalledTimes(2);
    });

    it("deletes a row once however fast Delete is confirmed", async () => {
        let answer;
        posted.mockImplementationOnce(() => new Promise(resolve => { answer = resolve; }));
        page();
        await click(".admin-data-row");
        const remove = dialog().querySelector(".admin-data-delete");

        remove.click();
        await flush();
        remove.click();
        await flush();
        answer(response(204));
        await flush();

        expect(posted).toHaveBeenCalledTimes(1);
    });

    it("creates a row, leaving its blank fields unset", async () => {
        posted.mockResolvedValue(response(201));
        page();
        await click("#admin-data-new");
        expect(fetchedUrls()).toEqual(["/spa/admin/data/chat-command/row"]);

        fill("id", "");
        fill("name", "!hi");
        fill("template", "");
        await click(".admin-data-save");

        expect(posted.mock.calls).toEqual([["/admin/data/chat-command/create", { name: "!hi" }]]);
    });

    it("says why a save is refused, and keeps the dialog open", async () => {
        posted.mockResolvedValue(response(400));
        page();
        await click(".admin-data-row");
        await click(".admin-data-save");

        expect(document.querySelector("mdui-snackbar").textContent).toBe("Invalid");
        expect(dialog().open).toBe(true);
    });

    it("deletes a confirmed row, or says it is still referenced", async () => {
        posted.mockResolvedValue(response(409));
        page();
        await click(".admin-data-row");
        await click(".admin-data-delete");

        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Delete it?" }));
        expect(posted.mock.calls).toEqual([[`/admin/data/chat-command/delete?id=${encodeURIComponent(ID)}`, {}]]);
        expect(document.querySelector("mdui-snackbar").textContent).toBe("Still referenced");
        expect(dialog().open).toBe(true);
    });

    it("sends nothing when the admin cancels a deletion", async () => {
        confirmAnswer = false;
        page();
        await click(".admin-data-row");
        await click(".admin-data-delete");

        expect(posted).not.toHaveBeenCalled();
    });

    it("says so when a row can't be fetched at all", async () => {
        fetched.mockRejectedValue(new TypeError("offline"));
        page();
        await click(".admin-data-row");

        expect(document.querySelector("mdui-snackbar").textContent).toBe("Failed (—)");
        expect(dialog().open).not.toBe(true);
    });

    it("says so when a save can't be sent at all, and keeps the dialog open", async () => {
        page();
        await click(".admin-data-row");
        posted.mockRejectedValue(new TypeError("offline"));
        fill("name", "!lurk");
        await click(".admin-data-save");

        expect(document.querySelector("mdui-snackbar").textContent).toBe("Failed (—)");
        expect(dialog().open).toBe(true);
    });

    it("falls back to the generic message for an unexpected status", async () => {
        fetched.mockResolvedValue(response(502, ""));
        page();
        await click(".admin-data-row");

        expect(document.querySelector("mdui-snackbar").textContent).toBe("Failed (502)");
        expect(dialog().open).not.toBe(true);
    });
});
