/*
 * A custom binding's schedule, in its row's form: the slots said in words, pause, delete, the editor (add, edit),
 * the next activation, the refusals told per code, and what is offered to a free account.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;
let showError;

const ART = "11111111-1111-1111-1111-111111111111";
const SLOT = "33333333-3333-3333-3333-333333333333";
const URL = `/channel/enimaloc/custom-bindings/${ART}/schedules`;
const DAYS = [["MONDAY", "L", "lundi"], ["TUESDAY", "Ma", "mardi"], ["WEDNESDAY", "Me", "mercredi"],
    ["THURSDAY", "J", "jeudi"], ["FRIDAY", "V", "vendredi"], ["SATURDAY", "S", "samedi"], ["SUNDAY", "D", "dimanche"]];

const weekly = {
    id: SLOT, startsAt: "2026-11-10T20:00:00", endsAt: "2026-11-10T23:00:00", repeat: "WEEKLY",
    days: ["TUESDAY", "THURSDAY"], repeatUntil: null, paused: false, suspended: false,
    nextStart: "2026-11-10T19:00:00Z"
};

const section = () => document.querySelector(".custom-binding-schedules");
const slots = () => Array.from(section().querySelectorAll(".custom-binding-schedule-list .custom-binding-schedule"));
const summary = row => row.querySelector(".custom-binding-schedule-summary").textContent;
const editor = () => section().querySelector(".custom-binding-schedule-editor");
const isHidden = el => el.classList.contains("hidden");
const next = () => section().querySelector(".custom-binding-schedule-next");

function slotRow(slot) {
    if (!slot) return "";
    return `<mdui-list-item class="custom-binding-schedule" data-schedule-id="${slot.id}"
                data-starts-at="${slot.startsAt.slice(0, 16)}" data-ends-at="${slot.endsAt.slice(0, 16)}"
                data-repeat="${slot.repeat}" data-days="${slot.days.join(",")}" data-paused="${slot.paused}"
                data-suspended="${slot.suspended}" data-next-start="${slot.nextStart}">
            <mdui-switch class="custom-binding-schedule-pause"${slot.paused ? "" : " checked"}></mdui-switch>
            <span class="custom-binding-schedule-summary">server</span>
            <span class="custom-binding-schedule-suspended hidden"></span>
            <mdui-button-icon class="custom-binding-schedule-edit"></mdui-button-icon>
            <mdui-button-icon class="custom-binding-schedule-delete"></mdui-button-icon>
        </mdui-list-item>`;
}

function page({ slot = weekly, gameId = "509660", canRepeat = true } = {}) {
    html(`
        <mdui-list id="channel-custom-bindings-list" data-error-invalid="Binding invalide">
            <mdui-collapse accordion>
                <mdui-collapse-item class="custom-binding-item" data-custom-binding-id="${ART}" value="${ART}">
                    <span class="custom-binding-name-label">Art</span><span class="custom-binding-game-label">Art</span>
                    <span class="custom-binding-active-chip hidden"></span>
                    <span class="custom-binding-uninterruptible-chip hidden"></span>
                    <input class="custom-binding-name" type="text" value="Art"/>
                    <input type="text" class="custom-binding-game-input" value="Art"/>
                    <input type="hidden" class="custom-binding-game-id" value="${gameId}"/>
                    <mdui-list class="custom-binding-game-results"></mdui-list>
                    <mdui-switch class="custom-binding-uninterruptible"></mdui-switch>
                    <div class="custom-binding-schedules" data-save-url="${URL}" data-game-id="${gameId}"
                         data-free-game-id="509663" data-can-repeat="${canRepeat}" data-zone="Europe/Paris"
                         data-format-time="HH:mm" data-format-date="dd/MM/yyyy" data-format-day-time="dd/MM HH:mm"
                         data-format-next="EEEE d MMMM, HH:mm" data-label-daily="chaque jour"
                         data-label-weekly="chaque semaine" data-label-until="jusqu'au {0}"
                         data-label-next="Prochaine activation : {0}"
                         data-error-premium-required="Ce créneau n'est pas disponible pour ce binding."
                         data-error-invalid="Créneau invalide">
                        <mdui-list class="custom-binding-schedule-list">${slotRow(slot)}</mdui-list>
                        <p class="custom-binding-schedule-empty${slot ? " hidden" : ""}"></p>
                        <mdui-button class="custom-binding-schedule-add"></mdui-button>
                        <span class="custom-binding-schedule-add-unavailable hidden"></span>
                        <p class="custom-binding-schedule-next${slot ? "" : " hidden"}">server</p>
                        <div class="custom-binding-schedule-editor hidden">
                            <mdui-text-field class="custom-binding-schedule-start"></mdui-text-field>
                            <mdui-text-field class="custom-binding-schedule-end"></mdui-text-field>
                            <mdui-segmented-button-group class="custom-binding-schedule-repeat" value="NONE">
                            </mdui-segmented-button-group>
                            <div class="custom-binding-schedule-weekly hidden">
                                ${DAYS.map(([day, short, name]) => `<mdui-chip class="custom-binding-schedule-day"
                                    value="${day}" data-name="${name}">${short}</mdui-chip>`).join("")}
                            </div>
                            <mdui-text-field class="custom-binding-schedule-until hidden"></mdui-text-field>
                            <mdui-button class="custom-binding-schedule-save"></mdui-button>
                            <mdui-button class="custom-binding-schedule-cancel"></mdui-button>
                        </div>
                    </div>
                </mdui-collapse-item>
            </mdui-collapse>
        </mdui-list>
        <p id="custom-bindings-error" class="hidden"></p>
        <template id="custom-binding-schedule-template">${slotRow({ ...weekly, id: "" }).replace(/ data-[a-z-]+="[^"]*"/g, "")}</template>`);
    document.querySelectorAll("mdui-switch").forEach(sw => { sw.checked = sw.hasAttribute("checked"); });
    render();
}

beforeEach(async () => {
    history.replaceState(null, "", "/channel/enimaloc");
    document.documentElement.lang = "fr";
    postJson = vi.fn(async () => response(204));
    showError = vi.fn();
    window.CatapultCsrf = { postJson };
    window.CatapultChannel = {
        baseUrl: () => "/channel/enimaloc", refresh: vi.fn(), showError,
        actionFailed: status => `failed ${status}`, unreachable: () => "unreachable",
        checkedValues: () => []
    };
    window.GameSearch = {
        attach: vi.fn((input, results, url, onSelect, options) => {
            let selected = options?.initial?.id ? options.initial : null;
            return { selected: () => selected, select: game => { selected = game; } };
        })
    };
    await load("channel-custom-bindings", "channel-custom-binding-schedules");
});

describe("custom binding schedule", () => {
    it("pauses a slot: posts the whole list and shows the answer, the next activation gone", async () => {
        page();
        postJson.mockResolvedValueOnce(response(200, [{ ...weekly, paused: true, nextStart: null }]));
        const pause = slots()[0].querySelector(".custom-binding-schedule-pause");
        pause.checked = false;
        pause.dispatchEvent(new Event("change"));
        await flush();

        expect(postJson).toHaveBeenCalledWith(URL, [{
            id: SLOT, startsAt: "2026-11-10T20:00", endsAt: "2026-11-10T23:00", repeat: "WEEKLY",
            days: ["TUESDAY", "THURSDAY"], repeatUntil: null, paused: true
        }]);
        expect(slots()).toHaveLength(1);
        expect(slots()[0].dataset.paused).toBe("true");
        expect(slots()[0].querySelector(".custom-binding-schedule-pause").checked).toBe(false);
        expect(summary(slots()[0])).toBe("Mardi, jeudi · 20:00 → 23:00 · chaque semaine");
        expect(isHidden(next())).toBe(true);
    });

    it("puts the switch back and tells the refusal in the section's own text", async () => {
        page();
        postJson.mockResolvedValueOnce(response(403, { code: "premium_required" }));
        const pause = slots()[0].querySelector(".custom-binding-schedule-pause");
        pause.checked = false;
        pause.dispatchEvent(new Event("change"));
        await flush();

        expect(showError).toHaveBeenCalledWith("Ce créneau n'est pas disponible pour ce binding.");
        expect(pause.checked).toBe(true);
        expect(summary(slots()[0])).toBe("server");
    });

    it("deletes a slot by posting the list without it", async () => {
        page();
        postJson.mockResolvedValueOnce(response(200, []));
        slots()[0].querySelector(".custom-binding-schedule-delete").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(URL, []);
        expect(slots()).toHaveLength(0);
        expect(isHidden(section().querySelector(".custom-binding-schedule-empty"))).toBe(false);
    });

    it("adds a one-off slot through the editor, and says it with its dates", async () => {
        page({ slot: null, gameId: "509663", canRepeat: false });
        section().querySelector(".custom-binding-schedule-add").click();
        expect(isHidden(editor())).toBe(false);
        expect(isHidden(section().querySelector(".custom-binding-schedule-add"))).toBe(true);
        editor().querySelector(".custom-binding-schedule-start").value = "2026-09-04T18:00";
        editor().querySelector(".custom-binding-schedule-end").value = "2026-09-07T00:00";
        const saved = { id: SLOT, startsAt: "2026-09-04T18:00:00", endsAt: "2026-09-07T00:00:00", repeat: "NONE",
            days: [], repeatUntil: null, paused: false, suspended: false, nextStart: "2026-09-04T16:00:00Z" };
        postJson.mockResolvedValueOnce(response(200, [saved]));
        editor().querySelector(".custom-binding-schedule-save").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(URL, [{
            id: null, startsAt: "2026-09-04T18:00:00", endsAt: "2026-09-07T00:00:00", repeat: "NONE", days: [],
            repeatUntil: null, paused: false
        }]);
        expect(isHidden(editor())).toBe(true);
        expect(summary(slots()[0])).toBe("04/09 18:00 → 07/09 00:00");
        expect(next().textContent).toBe("Prochaine activation : vendredi 4 septembre, 18:00");
        expect(isHidden(section().querySelector(".custom-binding-schedule-add"))).toBe(false);
    });

    it("edits a weekly slot: its days and its end date", async () => {
        page();
        slots()[0].querySelector(".custom-binding-schedule-edit").click();
        expect(editor().querySelector(".custom-binding-schedule-start").value).toBe("2026-11-10T20:00");
        expect(isHidden(editor().querySelector(".custom-binding-schedule-weekly"))).toBe(false);
        const chips = Array.from(editor().querySelectorAll(".custom-binding-schedule-day"));
        expect(chips.filter(chip => chip.selected).map(chip => chip.getAttribute("value")))
            .toEqual(["TUESDAY", "THURSDAY"]);
        chips[0].selected = true;
        editor().querySelector(".custom-binding-schedule-until").value = "2026-12-31";
        const saved = { ...weekly, days: ["MONDAY", "TUESDAY", "THURSDAY"], repeatUntil: "2026-12-31" };
        postJson.mockResolvedValueOnce(response(200, [saved]));
        editor().querySelector(".custom-binding-schedule-save").click();
        await flush();

        expect(postJson.mock.calls[0][1]).toEqual([{
            id: SLOT, startsAt: "2026-11-10T20:00:00", endsAt: "2026-11-10T23:00:00", repeat: "WEEKLY",
            days: ["MONDAY", "TUESDAY", "THURSDAY"], repeatUntil: "2026-12-31", paused: false
        }]);
        expect(summary(slots()[0])).toBe("Lundi, mardi, jeudi · 20:00 → 23:00 · chaque semaine · jusqu'au 31/12/2026");
        expect(next().textContent).toBe("Prochaine activation : mardi 10 novembre, 20:00");
    });

    it("keeps the editor open on a refusal, told in its own text", async () => {
        page();
        section().querySelector(".custom-binding-schedule-add").click();
        editor().querySelector(".custom-binding-schedule-save").click();
        await flush();
        expect(postJson).not.toHaveBeenCalled();
        expect(showError).toHaveBeenCalledWith("Créneau invalide");

        editor().querySelector(".custom-binding-schedule-start").value = "2026-11-10T23:00";
        editor().querySelector(".custom-binding-schedule-end").value = "2026-11-10T20:00";
        postJson.mockResolvedValueOnce(response(400, { code: "invalid" }));
        editor().querySelector(".custom-binding-schedule-save").click();
        await flush();
        expect(showError).toHaveBeenLastCalledWith("Créneau invalide");
        expect(isHidden(editor())).toBe(false);
        expect(slots()).toHaveLength(1);
    });

    it("offers adding on a free account only while the binding is on the free category", async () => {
        page({ slot: null, gameId: "509660", canRepeat: false });
        const add = section().querySelector(".custom-binding-schedule-add");
        const unavailable = section().querySelector(".custom-binding-schedule-add-unavailable");
        expect(isHidden(add)).toBe(true);
        expect(isHidden(unavailable)).toBe(false);

        // The binding moved to Special Events (a live event, or its save): adding is offered.
        CatapultCustomBindings.apply([{ id: ART, name: "Art", twitchGameId: "509663", twitchGameName: "Special Events",
            ccls: [], tws: [], uninterruptible: false }], null);
        expect(isHidden(add)).toBe(false);
        expect(isHidden(unavailable)).toBe(true);
    });

    it("shows the schedules a live event carries, and leaves them when it carries none", () => {
        page();
        const art = { id: ART, name: "Art", twitchGameId: "509660", twitchGameName: "Art", ccls: [], tws: [],
            uninterruptible: false };
        CatapultCustomBindings.apply([art], null);
        expect(summary(slots()[0])).toBe("server");

        CatapultCustomBindings.apply([art], null, undefined, { [ART]: [{ ...weekly, repeat: "DAILY", days: [] }] });
        expect(summary(slots()[0])).toBe("20:00 → 23:00 · chaque jour");
    });
});
