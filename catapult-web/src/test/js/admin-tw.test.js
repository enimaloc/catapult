/*
 * The admin Trigger warnings tab: the three rebuilds (each confirmed with its own text), adding a
 * definition (checked before posting, reloads), and the double click that posts once.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let posted;
let confirmAnswer;

const field = name => document.querySelector(`.admin-tw-create [name="${name}"]`);
const rebuildButton = mode => document.querySelector(`[data-rebuild="${mode}"]`);

function page() {
    html(`
        <mdui-tab-panel value="tw">
            <div class="admin-view admin-tw" data-confirm-text="OK" data-cancel-text="No"
                 data-label-invalid="Invalid" data-label-conflict="Taken" data-label-disabled="Off"
                 data-label-unavailable="Down" data-label-created="Created" data-label-rebuild-started="Started">
                <button class="admin-tw-rebuild" data-rebuild="untouched" data-confirm="Update never-analyzed?"></button>
                <button class="admin-tw-rebuild" data-rebuild="non-overridden" data-confirm="Rebuild non-pinned?"></button>
                <button class="admin-tw-rebuild" data-rebuild="force-pinned" data-confirm="OVERWRITE pinned?"></button>
                <div class="admin-tw-create">
                    <input name="id"><input name="label"><input name="sortOrder" value="0">
                    <button class="admin-tw-add-save"></button>
                </div>
            </div>
        </mdui-tab-panel>`);
    render();
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

const fill = (input, value) => {
    input.value = value;
    input.dispatchEvent(new Event("input", { bubbles: true }));
};

beforeEach(async () => {
    confirmAnswer = true;
    posted = vi.fn(async () => response(202));
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())), snackbar: vi.fn() };
    await load("admin-tabs", "admin-tw");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
});

describe("admin-tw", () => {
    it("confirms each rebuild with its own text", async () => {
        const expected = [
            ["untouched", "Update never-analyzed?"],
            ["non-overridden", "Rebuild non-pinned?"],
            ["force-pinned", "OVERWRITE pinned?"],
        ];
        for (const [mode, text] of expected) {
            await click(`[data-rebuild="${mode}"]`);
            expect(mdui.confirm).toHaveBeenLastCalledWith(expect.objectContaining({ headline: text }));
            expect(posted).toHaveBeenLastCalledWith(`/admin/tw/rebuild/${mode}`, {});
            expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Started" });
            expect(rebuildButton(mode).disabled).toBe(false);
        }
        expect(posted).toHaveBeenCalledTimes(3);
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
    });

    it("does not rebuild when the confirmation is declined", async () => {
        confirmAnswer = false;
        await click('[data-rebuild="force-pinned"]');

        expect(posted).not.toHaveBeenCalled();
        expect(rebuildButton("force-pinned").disabled).toBe(false);
    });

    it("tells a disabled feature and an unreachable api apart", async () => {
        posted.mockResolvedValueOnce(response(503));
        await click('[data-rebuild="untouched"]');
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Off");

        posted.mockResolvedValueOnce(response(502));
        await click('[data-rebuild="untouched"]');
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(window.mdui.snackbar).not.toHaveBeenCalled();
    });

    it("adds a definition and reloads", async () => {
        fill(field("id"), "gore");
        fill(field("label"), " Gore ");
        fill(field("sortOrder"), "5");
        await click(".admin-tw-add-save");

        expect(posted).toHaveBeenCalledTimes(1);
        expect(posted).toHaveBeenLastCalledWith("/admin/tw", { id: "gore", label: "Gore", sortOrder: 5 });
        expect(window.mdui.snackbar).toHaveBeenLastCalledWith({ message: "Created" });
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);
    });

    it("does not post a bad id, an empty or long label, or a negative order", async () => {
        for (const [id, label, order, bad] of [
            ["Bad Id", "L", "0", "id"], ["", "L", "0", "id"], ["ok", "", "0", "label"],
            ["ok", "x".repeat(81), "0", "label"], ["ok", "L", "-1", "sortOrder"],
        ]) {
            fill(field("id"), id);
            fill(field("label"), label);
            fill(field("sortOrder"), order);
            await click(".admin-tw-add-save");
            expect(field(bad).hasAttribute("invalid")).toBe(true);
        }
        expect(posted).not.toHaveBeenCalled();
    });

    it("tells a taken id and keeps the form; posts once on a double click", async () => {
        posted.mockResolvedValueOnce(response(409));
        fill(field("id"), "gore");
        fill(field("label"), "Gore");
        await click(".admin-tw-add-save");
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Taken");
        expect(field("id").value).toBe("gore");
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();

        posted.mockClear();
        const button = document.querySelector(".admin-tw-add-save");
        button.click();
        button.click();
        await flush();
        expect(posted).toHaveBeenCalledTimes(1);
    });
});
