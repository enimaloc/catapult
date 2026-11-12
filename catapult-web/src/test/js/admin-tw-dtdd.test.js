/*
 * The admin TW tab's DTDD topics: any change saves them all, in the order shown, with each one's least severe level,
 * then patches the list.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render } from "./helpers.js";

let tabs;
let sent;

function page() {
    html(`
        <mdui-tab-panel value="tw">
            <div class="admin-view admin-tw-dtdd-view" data-tw-id="gore" data-saved="Saved.">
                <div class="admin-tw-dtdd-row" data-topic="there's blood/gore">
                    <select class="admin-tw-dtdd-min"><option value=""></option><option value="Depicted" selected></option></select>
                    <button class="admin-tw-dtdd-remove"></button>
                </div>
                <div class="admin-tw-dtdd-row" data-topic="someone dies">
                    <select class="admin-tw-dtdd-min"><option value="" selected></option></select>
                    <button class="admin-tw-dtdd-remove"></button>
                </div>
                <input name="topic">
                <button class="admin-tw-dtdd-add"></button>
            </div>
        </mdui-tab-panel>`);
    render();
}

const q = selector => document.querySelector(selector);
const bodies = () => tabs.post.mock.calls.map(call => call[3].topics);

async function settle() {
    for (let i = 0; i < 10; i++) await Promise.resolve();
}

beforeEach(async () => {
    sent = "ok";
    tabs = { post: vi.fn(async () => sent), patch: vi.fn(async () => {}) };
    await load("admin-tw-dtdd");
    window.CatapultAdminTabs = tabs;
    window.mdui = { ...(window.mdui ?? {}), snackbar: vi.fn() };
    page();
});

describe("admin-tw-dtdd", () => {
    it("saves a level picked, with every topic as shown", async () => {
        const select = document.querySelectorAll(".admin-tw-dtdd-min")[1];
        select.innerHTML = '<option value="Implied" selected></option>';
        select.dispatchEvent(new Event("change", { bubbles: true }));
        await settle();

        expect(tabs.post.mock.calls[0][2]).toBe("/admin/tw/gore/dtdd");
        expect(tabs.post.mock.calls[0][1]).toBe(select);
        expect(bodies()[0]).toEqual([
            { topic: "there's blood/gore", minSeverity: "Depicted" },
            { topic: "someone dies", minSeverity: "Implied" }
        ]);
        expect(tabs.patch).toHaveBeenCalledTimes(1);
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Saved." });
    });

    it("removes a topic", async () => {
        document.querySelectorAll(".admin-tw-dtdd-remove")[0].click();
        await settle();

        expect(bodies()[0]).toEqual([{ topic: "someone dies", minSeverity: "" }]);
    });

    it("adds a topic at the end, without a level, and empties the field", async () => {
        q('[name="topic"]').value = "  there are spiders ";
        q(".admin-tw-dtdd-add").click();
        await settle();

        expect(bodies()[0].at(-1)).toEqual({ topic: "there are spiders", minSeverity: "" });
        expect(q('[name="topic"]').value).toBe("");
    });

    it("adds nothing blank nor a topic already there", async () => {
        q('[name="topic"]').value = "  ";
        q(".admin-tw-dtdd-add").click();
        q('[name="topic"]').value = "SOMEONE DIES";
        q(".admin-tw-dtdd-add").click();
        await settle();

        expect(tabs.post).not.toHaveBeenCalled();
    });

    it("keeps the field when the save is refused, and patches nothing", async () => {
        sent = 400;
        q('[name="topic"]').value = "x";
        q(".admin-tw-dtdd-add").click();
        await settle();

        expect(q('[name="topic"]').value).toBe("x");
        expect(tabs.patch).not.toHaveBeenCalled();
    });

    it("adds with Enter", async () => {
        q('[name="topic"]').value = "there are snakes";
        q('[name="topic"]').dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
        await settle();

        expect(bodies()[0].at(-1).topic).toBe("there are snakes");
    });
});
