/*
 * The admin Experiments detail view: each action posts to its route then reloads the view, End and
 * Reassign ask first, the forms show only the fields of the chosen type/target, weights show their
 * live share, and every refusal is told by its status.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, response } from "./helpers.js";

const EXP = "3f0c0000-0000-0000-0000-000000000001";
const V1 = "3f0c0000-0000-0000-0000-0000000000a1";
const V2 = "3f0c0000-0000-0000-0000-0000000000a2";

let posted;
let confirmAnswer;

const panel = () => document.querySelector('mdui-tab-panel[value="experiments"]');
const field = (form, name) => document.querySelector(`#${form} [name="${name}"]`);
const block = (kind, type) => document.querySelector(`[data-${kind}-fields="${type}"]`);

function page() {
    html(`
        <mdui-tab-panel value="experiments">
            <div class="admin-experiment-detail" data-experiment="${EXP}" data-confirm-text="OK" data-cancel-text="No"
                 data-label-invalid="Refused" data-label-unknown="Gone" data-label-account-unknown="No such account" data-label-unavailable="Down" data-label-saved="Saved"
                 data-label-end-confirm="End it?" data-label-reassign-confirm="Reassign?">
                <button data-experiment-action="activate"></button>
                <button data-experiment-action="end"></button>
                <button data-experiment-action="reassign"></button>

                <form id="experiment-weights">
                    <div><input data-variant="${V1}" name="weight-control" value="50"><span data-weight-share></span></div>
                    <div><input data-variant="${V2}" name="weight-enabled" value="50"><span data-weight-share></span></div>
                    <button type="submit" id="weights-save"></button>
                </form>

                <div data-rule-id="r" data-rule="rule-1">
                    <button data-rule-delete="rule-1" data-confirm="Delete rule?"></button>
                </div>
                <div data-override="ov-1">
                    <button data-override-delete="ov-1" data-confirm="Delete override?"></button>
                </div>

                <form id="experiment-rule-form">
                    <input name="type" value="RANDOM">
                    <div data-rule-fields="RANDOM"><input name="percentage" value="100"></div>
                    <div data-rule-fields="GROUP" hidden><input name="groupKey" value="beta"></div>
                    <div data-rule-fields="ATTRIBUTE" hidden>
                        <input name="attributeKey"><input name="attributeOperator" value="exists"><input name="attributeValue">
                    </div>
                    <div data-rule-fields="EXPERIMENT" hidden><input name="experimentKey"><input name="variantKey"></div>
                    <button type="submit" id="rule-save"></button>
                </form>

                <form id="experiment-override-form">
                    <input name="target" value="USER">
                    <div data-override-fields="USER"><input name="twitchUsername"></div>
                    <div data-override-fields="TAG" hidden>
                        <input name="tagKey" data-list="experiment-flag-keys"><input name="tagValue">
                        <datalist id="experiment-flag-keys">
                            <option value="beta" data-values="on,off"></option>
                            <option value="cohort" data-values=""></option>
                        </datalist>
                    </div>
                    <div data-override-fields="GROUP" hidden><input name="groupKey" value="beta"></div>
                    <input name="action" value="FORCE_INCLUDE">
                    <input name="variantId" value="${V2}">
                    <input name="priority" value="10">
                    <button type="submit" id="override-save"></button>
                </form>
            </div>
        </mdui-tab-panel>`);
    document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "experiments", panel: panel() } }));
}

async function click(selector) {
    document.querySelector(selector).click();
    await flush();
}

async function submit(id) {
    document.getElementById(id).form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await flush();
}

/** Sets a field the way the user would, firing the event mdui-select/-text-field emit. */
async function set(element, value, type = "input") {
    element.value = value;
    element.dispatchEvent(new Event(type, { bubbles: true }));
    await flush();
}

beforeEach(async () => {
    confirmAnswer = true;
    posted = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson: posted };
    window.mdui = { confirm: vi.fn(() => (confirmAnswer ? Promise.resolve() : Promise.reject())), snackbar: vi.fn() };
    await load("admin-tabs", "admin-experiments");
    window.CatapultChannel = { showError: vi.fn() };
    window.CatapultAdminTabs.reload = vi.fn(async () => {});
    page();
});

describe("admin-experiments", () => {
    it("posts the lifecycle action to its route and reloads the view", async () => {
        await click('[data-experiment-action="activate"]');

        expect(posted).toHaveBeenCalledWith(`/admin/experiments/${EXP}/activate`, {});
        expect(CatapultAdminTabs.reload).toHaveBeenCalledWith(panel());
    });

    it("posts nothing for end and reassign until confirmed", async () => {
        confirmAnswer = false;
        await click('[data-experiment-action="end"]');
        expect(window.mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "End it?" }));
        expect(posted).not.toHaveBeenCalled();

        confirmAnswer = true;
        await click('[data-experiment-action="end"]');
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/end`, {});

        await click('[data-experiment-action="reassign"]');
        expect(window.mdui.confirm).toHaveBeenLastCalledWith(expect.objectContaining({ headline: "Reassign?" }));
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/reassign`, {});
    });

    it("shows the live share of each weight and posts them by variant id", async () => {
        const shares = () => [...document.querySelectorAll("[data-weight-share]")].map(s => s.textContent);
        expect(shares()).toEqual(["50 %", "50 %"]);

        await set(document.querySelector(`[data-variant="${V1}"]`), "30");
        await set(document.querySelector(`[data-variant="${V2}"]`), "10");
        expect(shares()).toEqual(["75 %", "25 %"]);

        await submit("weights-save");
        expect(posted).toHaveBeenCalledWith(`/admin/experiments/${EXP}/weights`, { [V1]: 30, [V2]: 10 });
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("refuses a blank weight without posting", async () => {
        const first = document.querySelector(`[data-variant="${V1}"]`);
        await set(first, "");
        await submit("weights-save");

        expect(posted).not.toHaveBeenCalled();
        expect(first.hasAttribute("invalid")).toBe(true);
    });

    it("adds a tag override and posts the friendly body", async () => {
        await set(field("experiment-override-form", "target"), "TAG", "change");
        await set(field("experiment-override-form", "tagKey"), " beta ");
        await set(field("experiment-override-form", "tagValue"), "");
        await submit("override-save");

        expect(posted).toHaveBeenCalledWith(`/admin/experiments/${EXP}/overrides`, {
            target: "tag", tagKey: "beta", tagValue: null, action: "FORCE_INCLUDE", priority: 10,
        });
        expect(CatapultAdminTabs.reload).toHaveBeenCalled();
    });

    it("posts the account and group targets, and the variant only when forced", async () => {
        const form = "experiment-override-form";
        await set(field(form, "twitchUsername"), " enimaloc ");
        await set(field(form, "action"), "FORCE_VARIANT", "change");
        await submit("override-save");
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/overrides`, {
            target: "account", twitchUsername: "enimaloc", action: "FORCE_VARIANT", priority: 10, variantId: V2,
        });

        await set(field(form, "target"), "GROUP", "change");
        await set(field(form, "action"), "FORCE_EXCLUDE", "change");
        await submit("override-save");
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/overrides`, {
            target: "group", groupKey: "beta", action: "FORCE_EXCLUDE", priority: 10,
        });
    });

    it("shows only the fields of the chosen override target, and variantId only for FORCE_VARIANT", async () => {
        const form = "experiment-override-form";
        expect(block("override", "USER").hidden).toBe(false);
        expect(block("override", "TAG").hidden).toBe(true);
        expect(field(form, "variantId").hidden).toBe(true);

        await set(field(form, "target"), "TAG", "change");
        expect(block("override", "USER").hidden).toBe(true);
        expect(block("override", "TAG").hidden).toBe(false);
        expect(block("override", "GROUP").hidden).toBe(true);

        await set(field(form, "action"), "FORCE_VARIANT", "change");
        expect(field(form, "variantId").hidden).toBe(false);
        await set(field(form, "action"), "FORCE_INCLUDE", "change");
        expect(field(form, "variantId").hidden).toBe(true);
    });

    it("shows only the fields of the chosen rule type", async () => {
        await set(field("experiment-rule-form", "type"), "ATTRIBUTE", "change");

        expect(block("rule", "RANDOM").hidden).toBe(true);
        expect(block("rule", "ATTRIBUTE").hidden).toBe(false);
    });

    it("sends a tag rule's bare name as a flag: attribute key, with no value for exists", async () => {
        const form = "experiment-rule-form";
        await set(field(form, "type"), "ATTRIBUTE", "change");
        await set(field(form, "attributeKey"), " beta ");
        await submit("rule-save");

        expect(posted).toHaveBeenCalledWith(`/admin/experiments/${EXP}/rules`, {
            type: "ATTRIBUTE", attributeKey: "flag:beta", attributeOperator: "exists", attributeValue: null,
        });
    });

    it("posts a random rule's percentage, a group rule's group and an experiment rule's keys", async () => {
        const form = "experiment-rule-form";
        await set(field(form, "percentage"), "40");
        await submit("rule-save");
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/rules`, { type: "RANDOM", percentage: 40 });

        await set(field(form, "type"), "GROUP", "change");
        await submit("rule-save");
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/rules`, { type: "GROUP", groupKey: "beta" });

        await set(field(form, "type"), "EXPERIMENT", "change");
        await set(field(form, "experimentKey"), "other");
        await set(field(form, "variantKey"), "enabled");
        await submit("rule-save");
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/rules`, {
            type: "EXPERIMENT", experimentKey: "other", variantKey: "enabled",
        });
    });

    it("refuses a tag rule without a tag name", async () => {
        await set(field("experiment-rule-form", "type"), "ATTRIBUTE", "change");
        await submit("rule-save");

        expect(posted).not.toHaveBeenCalled();
        expect(field("experiment-rule-form", "attributeKey").hasAttribute("invalid")).toBe(true);
    });

    it("deletes a rule and an override after confirming", async () => {
        confirmAnswer = false;
        await click("[data-rule-delete]");
        expect(posted).not.toHaveBeenCalled();

        confirmAnswer = true;
        await click("[data-rule-delete]");
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/rules/rule-1/delete`, {});

        await click("[data-override-delete]");
        expect(window.mdui.confirm).toHaveBeenLastCalledWith(expect.objectContaining({ headline: "Delete override?" }));
        expect(posted).toHaveBeenLastCalledWith(`/admin/experiments/${EXP}/overrides/ov-1/delete`, {});
    });

    it("tells a 400, a 404 and a 409 (both reload), and an unreachable api", async () => {
        posted.mockResolvedValueOnce(response(400));
        await click('[data-experiment-action="activate"]');
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Refused");
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();

        posted.mockResolvedValueOnce(response(404));
        await click('[data-experiment-action="activate"]');
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Gone");
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(1);

        posted.mockResolvedValueOnce(response(409));
        await click('[data-experiment-action="activate"]');
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(2);

        posted.mockResolvedValueOnce(response(502));
        await click('[data-experiment-action="activate"]');
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
        expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(2);

        posted.mockRejectedValueOnce(new Error("offline"));
        await click('[data-experiment-action="activate"]');
        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("Down");
    });

    it("tells an unknown account without reloading, so the form keeps what was typed", async () => {
        const form = "experiment-override-form";
        await set(field(form, "twitchUsername"), "ghost");
        posted.mockResolvedValueOnce(response(404));
        await submit("override-save");

        expect(CatapultChannel.showError).toHaveBeenLastCalledWith("No such account");
        expect(CatapultAdminTabs.reload).not.toHaveBeenCalled();
        expect(field(form, "twitchUsername").value).toBe("ghost");
    });

    it("suggests the known tags and the chosen tag's values under the tag fields", async () => {
        const form = "experiment-override-form";
        await set(field(form, "target"), "TAG", "change");
        await set(field(form, "tagKey"), "be");
        const chips = () => [...document.querySelectorAll("[data-suggestion]")].map(c => c.textContent);
        expect(chips()).toEqual(["beta"]);

        document.querySelector("[data-suggestion]").click();
        expect(field(form, "tagKey").value).toBe("beta");
        expect(chips()).toEqual(["on", "off"]);

        document.querySelector("[data-suggestion]").click();
        expect(field(form, "tagValue").value).toBe("on");
    });
});
