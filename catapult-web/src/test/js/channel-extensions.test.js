/*
 * The extension layout of a configuration: the installed extensions read once per page, a slot's menu, a pick saved
 * with the whole layout (put back when refused), the reconnect line, an extension no longer installed struck through,
 * a layout built later (lazy.js) or opened once its custom binding is saved.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;

const INSTALLED = [
    { id: "mock-panel", name: "Build Guide", version: "1.2", types: ["PANEL"], canActivate: true },
    { id: "mock-overlay", name: "Live Map", version: "2.0", types: ["OVERLAY", "COMPONENT"], canActivate: true },
    { id: "mock-old", name: "Old Poll", version: "0.9", types: ["PANEL"], canActivate: false },
];

/** A slot as fragments/extensions.html renders it: `state` keep, none, or [id, name]. */
function slot(type, n, state = "keep", disabled = false) {
    const kind = Array.isArray(state) ? "extension" : state;
    const data = kind === "extension" ? ` data-extension-id="${state[0]}" data-extension-name="${state[1]}"` : "";
    const value = kind === "keep" ? "Garder" : kind === "none" ? "Aucune" : state[1];
    return `
        <mdui-dropdown class="extension-slot-dropdown">
            <button slot="trigger" type="button" class="extension-slot is-${kind}" data-type="${type}" data-slot="${n}"${data}
                    ${disabled ? "disabled" : ""}>
                <span class="extension-slot-label">${type} ${n}</span><span class="extension-slot-value">${value}</span>
            </button>
            <mdui-menu></mdui-menu>
        </mdui-dropdown>`;
}

function layout(prefix, { saveUrl = `/channel/enimaloc/${prefix}/extensions`, slots = {} } = {}) {
    const disabled = saveUrl == null;
    const at = (type, n) => slot(type, n, slots[`${type} ${n}`] ?? "keep", disabled);
    return `
        <div class="binding-extensions-panel${disabled ? " is-disabled" : ""}" data-extensions="${prefix}"
             ${disabled ? "" : `data-save-url="${saveUrl}"`}
             data-label-keep="Garder" data-label-none="Aucune" data-label-keep-current="Garder l'actuelle"
             data-label-no-extension="Aucune extension" data-label-unavailable="non activable"
             data-label-loading="Chargement" data-label-missing="Plus installée">
            ${at("OVERLAY", 1)}${at("COMPONENT", 1)}${at("COMPONENT", 2)}${at("PANEL", 1)}${at("PANEL", 2)}${at("PANEL", 3)}
            ${disabled ? '<p class="extension-layout-note">Enregistre le binding</p>' : ""}
            <p class="extension-layout-note extension-layout-empty hidden">Aucune extension trouvée</p>
        </div>`;
}

const layoutOf = prefix => document.querySelector(`[data-extensions="${prefix}"]`);
const slotOf = (prefix, type, n) => layoutOf(prefix).querySelector(`.extension-slot[data-type="${type}"][data-slot="${n}"]`);
const dropdownOf = (prefix, type, n) => slotOf(prefix, type, n).closest("mdui-dropdown");
const value = (prefix, type, n) => slotOf(prefix, type, n).querySelector(".extension-slot-value").textContent;
const emptyLine = prefix => layoutOf(prefix).querySelector(".extension-layout-empty");

/** Opens the slot's menu as mdui does (an open event on the dropdown) and answers its items. */
function open(prefix, type, n) {
    const dropdown = dropdownOf(prefix, type, n);
    dropdown.open = true;
    dropdown.dispatchEvent(new CustomEvent("open", { bubbles: true, composed: true }));
    return Array.from(dropdown.querySelectorAll("mdui-menu-item"));
}

function choose(items, text) {
    items.find(item => item.textContent === text).dispatchEvent(new MouseEvent("click", { bubbles: true }));
}

function stubInstalled(installed = INSTALLED, status = 200) {
    vi.stubGlobal("fetch", vi.fn(async () => response(status, { installed, active: [] })));
}

beforeEach(async () => {
    history.replaceState(null, "", "/channel/enimaloc");
    postJson = vi.fn(async () => ({ ok: true, status: 204 }));
    window.CatapultChannel = { baseUrl: () => "/channel/enimaloc", postJson };
    stubInstalled();
    await load("channel-extensions");
});

describe("the installed extensions", () => {
    it("are asked once for every layout of the page", async () => {
        html(layout("binding") + layout("no-game") + layout("incomplete-fallback"));
        render();
        await flush();

        expect(fetch).toHaveBeenCalledTimes(1);
        expect(fetch).toHaveBeenCalledWith("/channel/enimaloc/twitch/extensions", expect.anything());
        expect(emptyLine("binding").classList.contains("hidden")).toBe(true);
    });

    it("aren't asked by a page without a layout", async () => {
        html("<p>admin</p>");
        render();
        await flush();

        expect(fetch).not.toHaveBeenCalled();
    });

    it("are asked again by the next page", async () => {
        html(layout("binding"));
        render();
        await flush();
        render();
        await flush();

        expect(fetch).toHaveBeenCalledTimes(2);
    });

    it("listed by Twitch as none: each layout asks to reconnect", async () => {
        stubInstalled([]);
        html(layout("binding") + layout("no-game"));
        render();
        await flush();

        expect(emptyLine("binding").classList.contains("hidden")).toBe(false);
        expect(emptyLine("no-game").classList.contains("hidden")).toBe(false);
    });

    it("not read at all: no reconnect line, the menu only keeps or empties", async () => {
        stubInstalled([], 502);
        html(layout("binding"));
        render();
        await flush();

        expect(emptyLine("binding").classList.contains("hidden")).toBe(true);
        expect(open("binding", "PANEL", 1).map(item => item.textContent)).toEqual(["Garder l'actuelle", "Aucune extension"]);
    });

    it("a fetch that fails tells nothing either", async () => {
        vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("offline"); }));
        html(layout("binding"));
        render();
        await flush();

        expect(emptyLine("binding").classList.contains("hidden")).toBe(true);
    });

    it("strike through a slot showing an extension no longer installed", async () => {
        html(layout("binding", { slots: { "PANEL 1": ["gone", "Old Thing"], "PANEL 2": ["mock-panel", "Build Guide"] } }));
        render();
        await flush();

        expect(slotOf("binding", "PANEL", 1).classList.contains("is-missing")).toBe(true);
        expect(slotOf("binding", "PANEL", 1).title).toBe("Plus installée");
        expect(value("binding", "PANEL", 1)).toBe("Old Thing");
        expect(slotOf("binding", "PANEL", 2).classList.contains("is-missing")).toBe(false);
    });

    it("listed as none, nothing is struck through: Twitch couldn't be read", async () => {
        stubInstalled([]);
        html(layout("binding", { slots: { "PANEL 1": ["gone", "Old Thing"] } }));
        render();
        await flush();

        expect(slotOf("binding", "PANEL", 1).classList.contains("is-missing")).toBe(false);
    });
});

describe("a slot's menu", () => {
    beforeEach(async () => {
        html(layout("binding", { slots: { "PANEL 2": ["mock-panel", "Build Guide"] } }));
        render();
        await flush();
    });

    it("keeps, empties, then lists the installed extensions of its type, those Twitch won't activate greyed", () => {
        const items = open("binding", "PANEL", 2);

        expect(items.map(item => item.textContent)).toEqual(["Garder l'actuelle", "Aucune extension", "Build Guide", "Old Poll"]);
        expect(items[2].endIcon).toBe("check");
        expect(items[2].endText).toBe("v1.2");
        expect(items[0].endIcon).toBeUndefined();
        expect(items[3].disabled).toBe(true);
        expect(items[3].endText).toBe("non activable");
    });

    it("of a component lists those accepting components", () => {
        const items = open("binding", "COMPONENT", 1);

        expect(items.map(item => item.textContent)).toEqual(["Garder l'actuelle", "Aucune extension", "Live Map"]);
        expect(items[0].endIcon).toBe("check");
    });

    it("opens and closes without the row it is in hearing it", () => {
        const row = vi.fn();
        layoutOf("binding").parentElement.addEventListener("open", row);
        layoutOf("binding").parentElement.addEventListener("closed", row);
        open("binding", "PANEL", 1);
        dropdownOf("binding", "PANEL", 1).dispatchEvent(new CustomEvent("closed", { bubbles: true }));

        expect(row).not.toHaveBeenCalled();
    });

    it("leaves the events of what it holds alone", () => {
        const row = vi.fn();
        document.body.addEventListener("open", row);
        slotOf("binding", "PANEL", 1).dispatchEvent(new CustomEvent("open", { bubbles: true }));

        expect(row).toHaveBeenCalled();
    });
});

describe("a pick", () => {
    beforeEach(async () => {
        html(layout("binding", { slots: { "OVERLAY 1": "none", "PANEL 2": ["mock-panel", "Build Guide"] } }));
        render();
        await flush();
    });

    it("shows at once and saves the whole layout, kept slots left out", async () => {
        choose(open("binding", "COMPONENT", 2), "Live Map");
        await flush();

        expect(value("binding", "COMPONENT", 2)).toBe("Live Map");
        expect(slotOf("binding", "COMPONENT", 2).classList.contains("is-extension")).toBe(true);
        expect(slotOf("binding", "COMPONENT", 2).classList.contains("is-keep")).toBe(false);
        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/binding/extensions", [
            { type: "OVERLAY", slot: 1, extensionId: null, extensionName: null },
            { type: "COMPONENT", slot: 2, extensionId: "mock-overlay", extensionName: "Live Map" },
            { type: "PANEL", slot: 2, extensionId: "mock-panel", extensionName: "Build Guide" },
        ]);
    });

    it("of keep or none leaves out or empties the slot", async () => {
        choose(open("binding", "PANEL", 2), "Garder l'actuelle");
        await flush();
        expect(value("binding", "PANEL", 2)).toBe("Garder");
        expect(slotOf("binding", "PANEL", 2).dataset.extensionId).toBeUndefined();
        expect(postJson).toHaveBeenLastCalledWith("/channel/enimaloc/binding/extensions", [
            { type: "OVERLAY", slot: 1, extensionId: null, extensionName: null },
        ]);

        choose(open("binding", "PANEL", 3), "Aucune extension");
        await flush();
        expect(value("binding", "PANEL", 3)).toBe("Aucune");
        expect(slotOf("binding", "PANEL", 3).classList.contains("is-none")).toBe(true);
        expect(postJson).toHaveBeenCalledTimes(2);
    });

    it("refused, puts the slot back as it was", async () => {
        postJson.mockResolvedValueOnce({ ok: false, status: 502 });
        choose(open("binding", "PANEL", 2), "Aucune extension");
        await flush();

        expect(value("binding", "PANEL", 2)).toBe("Build Guide");
        expect(slotOf("binding", "PANEL", 2).classList.contains("is-extension")).toBe(true);
        expect(slotOf("binding", "PANEL", 2).dataset.extensionId).toBe("mock-panel");
    });

    it("of what the slot shows already, or of a greyed one, sends nothing", async () => {
        choose(open("binding", "PANEL", 2), "Build Guide");
        choose(open("binding", "PANEL", 1), "Old Poll");
        await flush();

        expect(postJson).not.toHaveBeenCalled();
        expect(value("binding", "PANEL", 1)).toBe("Garder");
    });

    it("while the layout is being saved, waits its turn", async () => {
        let answer;
        postJson.mockReturnValueOnce(new Promise(resolve => { answer = resolve; }));
        choose(open("binding", "PANEL", 1), "Build Guide");
        choose(open("binding", "PANEL", 3), "Build Guide");
        answer({ ok: true, status: 204 });
        await flush();

        expect(postJson).toHaveBeenCalledTimes(1);
        expect(value("binding", "PANEL", 3)).toBe("Garder");
        expect(layoutOf("binding").classList.contains("is-saving")).toBe(false);
    });

    it("of an extension without a name shows its id and sends no name", async () => {
        stubInstalled([{ id: "nameless", name: "", version: "", types: ["PANEL"], canActivate: true }]);
        render();
        await flush();
        choose(open("binding", "PANEL", 1), "nameless");
        await flush();

        expect(value("binding", "PANEL", 1)).toBe("nameless");
        expect(postJson.mock.calls[0][1]).toContainEqual({ type: "PANEL", slot: 1, extensionId: "nameless", extensionName: null });
        expect(open("binding", "PANEL", 1)[2].endText).toBeUndefined();
    });
});

describe("a menu opened before the extensions are known", () => {
    it("says they are loading, then lists them while still open", async () => {
        let answer;
        vi.stubGlobal("fetch", vi.fn(() => new Promise(resolve => { answer = resolve; })));
        html(layout("binding"));
        render();

        const items = open("binding", "PANEL", 1);
        expect(items.map(item => item.textContent)).toEqual(["Garder l'actuelle", "Aucune extension", "Chargement"]);
        expect(items[2].disabled).toBe(true);
        choose(items, "Chargement");

        answer(response(200, { installed: INSTALLED, active: [] }));
        await flush();
        const menu = dropdownOf("binding", "PANEL", 1).querySelectorAll("mdui-menu-item");
        expect(Array.from(menu, item => item.textContent)).toContain("Build Guide");
        expect(postJson).not.toHaveBeenCalled();
    });

    it("of another page drops what this one's request answers late", async () => {
        const answers = [];
        vi.stubGlobal("fetch", vi.fn(() => new Promise(resolve => { answers.push(resolve); })));
        html(layout("binding"));
        render();
        render();
        answers[0](response(200, { installed: [], active: [] }));
        await flush();

        // The first page's empty list isn't this one's: no reconnect line, still loading.
        expect(emptyLine("binding").classList.contains("hidden")).toBe(true);
        expect(open("binding", "PANEL", 1).map(item => item.textContent)).toContain("Chargement");
    });
});

describe("a layout built later", () => {
    it("is wired when its body is shown (lazy.js)", async () => {
        html(`<div id="row"></div>${layout("no-game")}`);
        render();
        await flush();
        const row = document.getElementById("row");
        row.innerHTML = layout("binding");
        row.dispatchEvent(new CustomEvent("catapult:lazy", { bubbles: true, detail: { root: row } }));
        await flush();

        expect(fetch).toHaveBeenCalledTimes(1);
        choose(open("binding", "PANEL", 1), "Build Guide");
        await flush();
        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/binding/extensions", expect.any(Array));
    });

    it("a lazy event without an element root is ignored", () => {
        document.dispatchEvent(new CustomEvent("catapult:lazy", { detail: {} }));
        expect(fetch).not.toHaveBeenCalled();
    });
});

describe("a custom binding not saved yet", () => {
    it("is disabled till enable() gives it its route", async () => {
        html(`<div id="row">${layout("custom-binding", { saveUrl: null })}</div>`);
        render();
        await flush();
        choose(open("custom-binding", "PANEL", 1), "Build Guide");
        await flush();
        expect(postJson).not.toHaveBeenCalled();

        window.CatapultExtensions.enable(document.getElementById("row"), "/channel/enimaloc/custom-bindings/42/extensions");
        expect(layoutOf("custom-binding").classList.contains("is-disabled")).toBe(false);
        expect(slotOf("custom-binding", "PANEL", 1).disabled).toBe(false);
        expect(layoutOf("custom-binding").querySelectorAll(".extension-layout-note")).toHaveLength(1);
        choose(open("custom-binding", "PANEL", 1), "Aucune extension");
        await flush();

        expect(postJson).toHaveBeenCalledTimes(1);
        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/custom-bindings/42/extensions",
            [{ type: "PANEL", slot: 1, extensionId: null, extensionName: null }]);
    });

    it("enable() of a row without a layout does nothing", () => {
        html('<div id="row"></div>');
        expect(() => window.CatapultExtensions.enable(document.getElementById("row"), "/x")).not.toThrow();
        expect(() => window.CatapultExtensions.enable(null, "/x")).not.toThrow();
    });
});
