/*
 * The admin Experiments detail view: each action posts to its route then reloads the view, End and
 * Reassign ask first, the forms show only the fields of the chosen type/target, weights show their
 * live share, and every refusal is told by its status.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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

describe("admin-experiments weight bar", () => {
    const V3 = "3f0c0000-0000-0000-0000-0000000000a3";
    const ids = [V1, V2, V3];
    const input = id => document.querySelector(`#experiment-weights [data-variant="${id}"]`);
    const values = () => ids.map(id => input(id).value);
    const grow = id => document.querySelector(`[data-segment="${id}"]`).style.flexGrow;
    const handle = n => document.querySelector(`[data-handle="${n}"]`);

    /** The detail with a three-variant bar 200px wide starting at x=100. */
    function barPage(weights) {
        html(`
            <mdui-tab-panel value="experiments">
                <div class="admin-experiment-detail" data-experiment="${EXP}"
                     data-label-invalid="Refused" data-label-unknown="Gone" data-label-unavailable="Down">
                    <form id="experiment-weights">
                        <div class="experiment-weight-bar is-editable">
                            <div class="experiment-weight-segment" data-segment="${V1}"><span class="experiment-weight-label"><span>a</span> <b></b></span></div>
                            <span class="experiment-weight-handle" role="slider" tabindex="0" data-handle="1"></span>
                            <div class="experiment-weight-segment" data-segment="${V2}"><span class="experiment-weight-label"><span>b</span> <b></b></span></div>
                            <span class="experiment-weight-handle" role="slider" tabindex="0" data-handle="2"></span>
                            <div class="experiment-weight-segment" data-segment="${V3}"><span class="experiment-weight-label"><span>c</span> <b></b></span></div>
                        </div>
                        ${ids.map((id, i) => `<div><input data-variant="${id}" value="${weights[i]}"><span data-weight-share></span></div>`).join("")}
                        <button type="submit" id="weights-save"></button>
                    </form>
                </div>
            </mdui-tab-panel>`);
        document.querySelector(".experiment-weight-bar").getBoundingClientRect = () => ({ left: 100, width: 200 });
        document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "experiments", panel: panel() } }));
    }

    async function drag(n, toX) {
        const grip = handle(n);
        grip.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, clientX: 0 }));
        grip.dispatchEvent(new MouseEvent("pointermove", { bubbles: true, clientX: toX }));
        grip.dispatchEvent(new MouseEvent("pointerup", { bubbles: true, clientX: toX }));
        await flush();
    }

    async function key(n, name, shiftKey = false) {
        handle(n).dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, cancelable: true, key: name, shiftKey }));
        await flush();
    }

    it("turns stored weights into percentages summing to 100, and sizes the segments by them", () => {
        barPage([1, 3, 0]);

        expect(values()).toEqual(["25", "75", "0"]);
        expect([grow(V1), grow(V2), grow(V3)]).toEqual(["25", "75", "0"]);
        expect(document.querySelector(`[data-segment="${V1}"] b`).textContent).toBe("25 %");
    });

    it("keeps the sum at 100 when rounding, the largest remainders taking the points", () => {
        barPage([1, 1, 1]);

        expect(values().map(Number).reduce((a, b) => a + b)).toBe(100);
        expect(values()).toEqual(["34", "33", "33"]);
    });

    it("leaves weights that are all zero alone", () => {
        barPage([0, 0, 0]);

        expect(values()).toEqual(["0", "0", "0"]);
    });

    it("moves points between the two neighbours of the dragged handle only, then posts them", async () => {
        barPage([20, 30, 50]);

        await drag(1, 100 + 200 * 0.4); // the first boundary to 40 %

        expect(values()).toEqual(["40", "10", "50"]);
        expect(grow(V1)).toBe("40");
        await submit("weights-save");
        expect(posted).toHaveBeenCalledWith(`/admin/experiments/${EXP}/weights`, { [V1]: 40, [V2]: 10, [V3]: 50 });
    });

    it("stops a handle at its neighbours' edges", async () => {
        barPage([20, 30, 50]);

        await drag(2, 100 + 200 * 0.1); // left of the first boundary: the middle variant empties

        expect(values()).toEqual(["20", "0", "80"]);

        await drag(2, 100 + 200 * 1.5); // past the bar's end: the last variant empties

        expect(values()).toEqual(["20", "80", "0"]);
    });

    it("moves a handle by 1 with the arrows and 10 with Shift, and tells its position", async () => {
        barPage([20, 30, 50]);

        await key(1, "ArrowRight");
        expect(values()).toEqual(["21", "29", "50"]);

        await key(1, "ArrowLeft", true);
        expect(values()).toEqual(["11", "39", "50"]);
        expect(handle(1).getAttribute("aria-valuenow")).toBe("11");
        expect(handle(2).getAttribute("aria-valuenow")).toBe("50");
    });

    it("follows the number fields as they are typed", async () => {
        barPage([20, 30, 50]);

        await set(input(V3), "150");

        expect(grow(V3)).toBe("150");
        expect(document.querySelector(`[data-segment="${V3}"] b`).textContent).toBe("75 %");
    });
});

describe("admin-experiments participants", () => {
    const USER = "3f0c0000-0000-0000-0000-0000000000b1";
    const base = `/spa/admin/experiments/${EXP}`;
    let served;
    let fetched;

    const participants = (variant = "", q = "", page = 0, rows = "<div data-row>row</div>") => `
        <div data-participants ${variant ? `data-variant="${variant}"` : ""} ${q ? `data-q="${q}"` : ""} data-page="${page}">
            <div data-participants-chips>
                <button data-participants-variant="" ${variant ? "" : "selected"}>all</button>
                <button data-participants-variant="${V1}" ${variant === V1 ? "selected" : ""}>one</button>
                <button data-participants-variant="${V2}" ${variant === V2 ? "selected" : ""}>two</button>
            </div>
            <input data-participants-search>
            <div data-participants-list>${rows}
                <button data-participants-page="${page + 1}"></button>
                <select data-participants-move="${USER}"><option value=""></option><option value="${V2}">two</option></select>
            </div>
        </div>`;

    const overridesCard = label => `<div id="experiment-overrides"><span data-fresh>${label}</span>
        <form id="experiment-override-form"><input name="target" value="USER"><input name="action" value="FORCE_INCLUDE"><input name="variantId" value="${V2}"></form></div>`;

    /** The detail fragment: just enough for the overrides card to be found in it. */
    const detail = label => `<div class="admin-experiment-detail">${participants()}${overridesCard(label)}</div>`;

    const urls = () => fetched.mock.calls.map(([url]) => url);

    beforeEach(async () => {
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
        served = {};
        fetched = vi.fn(async url => response(200, url.includes("/participants")
            ? (served[url] ?? participants()) : detail("fresh")));
        window.fetch = fetched;
        document.querySelector(".admin-experiment-detail").insertAdjacentHTML("beforeend", participants() + overridesCard("old"));
    });

    afterEach(() => vi.useRealTimers());

    const settle = () => vi.advanceTimersByTimeAsync(0);

    /** The user picking `value` in a move select. */
    async function pick(select, value) {
        select.value = value;
        select.dispatchEvent(new Event("change", { bubbles: true }));
        await settle();
    }

    it("reloads the list on a variant chip, from page 0 and with the search kept", async () => {
        document.querySelector("[data-participants]").dataset.q = "al";
        document.querySelector("[data-participants]").dataset.page = "2";

        document.querySelector(`[data-participants-variant="${V1}"]`).click();
        await settle();

        expect(urls()).toEqual([`${base}/participants?variant=${V1}&q=al&page=0`]);
    });

    it("swaps the chips and the list in, keeps the search field, and takes the new state over", async () => {
        served[`${base}/participants?variant=${V1}&q=&page=0`] = participants(V1, "", 0, "<div data-row>fresh row</div>");
        const search = document.querySelector("[data-participants-search]");

        document.querySelector(`[data-participants-variant="${V1}"]`).click();
        await settle();

        expect(document.querySelector("[data-participants-list]").textContent).toContain("fresh row");
        expect(document.querySelector(`[data-participants-variant="${V1}"]`).hasAttribute("selected")).toBe(true);
        expect(document.querySelector("[data-participants]").dataset.variant).toBe(V1);
        expect(document.querySelector("[data-participants-search]")).toBe(search);
    });

    it("searches 300 ms after the last keystroke, from page 0, within the chosen variant", async () => {
        document.querySelector("[data-participants]").dataset.variant = V2;
        const search = document.querySelector("[data-participants-search]");

        search.value = "b";
        search.dispatchEvent(new Event("input", { bubbles: true }));
        await vi.advanceTimersByTimeAsync(200);
        search.value = "bo";
        search.dispatchEvent(new Event("input", { bubbles: true }));
        await vi.advanceTimersByTimeAsync(200);
        expect(fetched).not.toHaveBeenCalled();

        await vi.advanceTimersByTimeAsync(100);

        expect(urls()).toEqual([`${base}/participants?variant=${V2}&q=bo&page=0`]);
    });

    it("goes to the page of a pager button, keeping the filter and the search", async () => {
        const card = document.querySelector("[data-participants]");
        card.dataset.variant = V1;
        card.dataset.q = "al";

        document.querySelector("[data-participants-page]").click();
        await settle();

        expect(urls()).toEqual([`${base}/participants?variant=${V1}&q=al&page=1`]);
    });

    it("posts the chosen variant, then refreshes the list in place and the overrides card", async () => {
        const card = document.querySelector("[data-participants]");
        card.dataset.variant = V1;
        card.dataset.page = "1";
        const select = document.querySelector("[data-participants-move]");

        await pick(select, V2);

        expect(posted).toHaveBeenCalledWith(`/admin/experiments/${EXP}/assignments/${USER}/variant`, { variantId: V2 });
        expect(urls()).toEqual([`${base}/participants?variant=${V1}&q=&page=1`, base]);
        expect(document.querySelector("#experiment-overrides [data-fresh]").textContent).toBe("fresh");
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Saved" });
    });

    it.each([[400, "Refused", false], [404, "Gone", true], [409, "Down", true], [502, "Down", false]])(
        "tells a %i refusal, resets the select, and fetches nothing", async (status, message, reloads) => {
            posted.mockResolvedValue(response(status));
            const select = document.querySelector("[data-participants-move]");

            await pick(select, V2);

            expect(CatapultChannel.showError).toHaveBeenCalledWith(message);
            expect(select.value).toBe("");
            expect(select.disabled).toBe(false);
            expect(fetched).not.toHaveBeenCalled();
            expect(CatapultAdminTabs.reload).toHaveBeenCalledTimes(reloads ? 1 : 0);
        });

    it("frees the move select when the refresh after a move fails", async () => {
        fetched.mockRejectedValue(new Error("offline"));
        const select = document.querySelector("[data-participants-move]");

        await pick(select, V2);

        expect(select.disabled).toBe(false);
        expect(CatapultChannel.showError).toHaveBeenCalledWith("Down");
    });
});
