/*
 * The OBS dock: its channel-wide CCL and TW switches (sent with the blocked lists they leave as
 * they are), the switches of the binding of the game played now, and the coalesced re-render its
 * events ask for, which keeps where the dock was scrolled to.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;

function sw(attrs, checked = false, value = "") {
    return `<mdui-switch ${attrs} data-checked="${checked}" data-value="${value}"></mdui-switch>`;
}

/** mdui-switch's checked and value are element properties, which jsdom's unknown element hasn't. */
function hydrate() {
    document.querySelectorAll("mdui-switch").forEach(el => {
        el.checked = el.dataset.checked === "true";
        el.value = el.dataset.value;
    });
}

function dock(withBinding = true) {
    html(`
        <section id="obs-dock">
            ${sw(`id="obs-dock-ccl-enabled" data-blocked="ccl-b,ccl-c"`, true)}
            ${sw(`id="obs-dock-tw-enabled" data-blocked=""`)}
            <div class="obs-dock-binding" ${withBinding ? `data-binding-id="b1" data-game-id="508455" data-game-name="Valheim"` : ""}>
                ${withBinding ? `
                    ${sw(`id="obs-dock-binding-ccl-enabled"`, true)}
                    ${sw(`class="obs-dock-binding-ccl"`, true, "ccl-a")}
                    ${sw(`class="obs-dock-binding-ccl"`, false, "ccl-d")}
                    ${sw(`id="obs-dock-binding-tw-enabled"`)}
                    ${sw(`class="obs-dock-binding-tw"`, false, "tw-a")}
                    ${sw(`class="obs-dock-binding-tw"`, true, "tw-b")}` : ""}
            </div>
        </section>`);
    hydrate();
    render();
}

function flip(el) {
    el.checked = !el.checked;
    el.dispatchEvent(new Event("change"));
}

const byId = id => document.getElementById(id);
const all = selector => document.querySelectorAll(selector);

beforeEach(async () => {
    history.replaceState(null, "", "/channel/enimaloc/dock");
    postJson = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    await load("channel-page", "channel-dock");
});

describe("active", () => {
    it("is whether the page is the dock", () => {
        expect(CatapultDock.active()).toBe(false);
        dock();
        expect(CatapultDock.active()).toBe(true);
    });
});

describe("channel-wide switches", () => {
    it("send the CCL feature's state with the blocked CCL unchanged", async () => {
        dock();
        flip(byId("obs-dock-ccl-enabled"));
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/settings/ccl",
            { cclEnabled: false, blockedCcls: ["ccl-b", "ccl-c"] });
    });

    it("send the TW feature's state, with no blocked TW when there are none", async () => {
        dock();
        flip(byId("obs-dock-tw-enabled"));
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/settings/tws", { enabled: true, blockedTws: [] });
    });

    it("are put back when the change doesn't go through", async () => {
        postJson.mockResolvedValue(response(500));
        dock();
        flip(byId("obs-dock-ccl-enabled"));
        await flush();

        expect(byId("obs-dock-ccl-enabled").checked).toBe(true);
    });

    it("still work without a binding for the game played now", async () => {
        dock(false);
        flip(byId("obs-dock-tw-enabled"));
        await flush();

        expect(postJson).toHaveBeenCalledTimes(1);
    });
});

describe("the binding of the game played now", () => {
    it("toggles its CCL", async () => {
        dock();
        flip(byId("obs-dock-binding-ccl-enabled"));
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/bindings/b1/ccl-toggle", { enabled: false });
    });

    it("saves its CCL with its game, which catapult-api replaces along with them", async () => {
        dock();
        flip(all(".obs-dock-binding-ccl")[1]);
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/bindings/b1",
            { twitchGameId: "508455", twitchGameName: "Valheim", ccls: ["ccl-a", "ccl-d"] });
    });

    it("toggles and saves its TW through the binding-scoped routes", async () => {
        dock();
        flip(byId("obs-dock-binding-tw-enabled"));
        flip(all(".obs-dock-binding-tw")[1]);
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/bindings/b1/tw-enabled", { enabled: true });
        expect(postJson).toHaveBeenCalledWith("/channel/bindings/b1/tws", { tws: [] });
    });

    it("sends a missing game as null", async () => {
        dock();
        const binding = document.querySelector(".obs-dock-binding");
        delete binding.dataset.gameId;
        delete binding.dataset.gameName;
        flip(all(".obs-dock-binding-ccl")[0]);
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/bindings/b1",
            { twitchGameId: null, twitchGameName: null, ccls: [] });
    });
});

describe("refresh", () => {
    it("re-renders once for a burst of events, keeping the scroll", async () => {
        vi.useFakeTimers();
        dock();
        window.scrollY = 240;
        const scrollTo = vi.spyOn(window, "scrollTo").mockImplementation(() => {});

        CatapultDock.refresh();
        CatapultDock.refresh();
        await vi.advanceTimersByTimeAsync(200);

        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(1);
        expect(CatapultSpa.navigate).toHaveBeenCalledWith("channel/enimaloc/dock", false, "");
        expect(scrollTo).toHaveBeenCalledWith({ top: 240, behavior: "instant" });

        CatapultDock.refresh();
        await vi.advanceTimersByTimeAsync(200);
        expect(CatapultSpa.navigate).toHaveBeenCalledTimes(2);
    });
});

it("does nothing outside the dock", async () => {
    html(`${sw(`id="obs-dock-ccl-enabled"`)}`);
    hydrate();
    render();
    flip(byId("obs-dock-ccl-enabled"));
    await flush();

    expect(postJson).not.toHaveBeenCalled();
});
