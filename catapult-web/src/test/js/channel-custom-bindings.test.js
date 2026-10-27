/*
 * The custom bindings: the overview list (activate, deactivate, the error told per code, create through
 * "New", edit in a row's form, confirmed delete) and the personal API token card (status, confirmed
 * regeneration, the token shown once with its curl example, copy).
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;
let showError;
let refresh;
let pick;

const ART = "11111111-1111-1111-1111-111111111111";
const DEV = "22222222-2222-2222-2222-222222222222";

const byId = id => document.getElementById(id);
const item = id => document.querySelector(`#channel-custom-bindings-list [data-custom-binding-id="${id}"]`);
const fresh = () => document.querySelector("#channel-custom-bindings-list .custom-binding-new");
const activateBtn = id => item(id).querySelector(".custom-binding-activate");
const stopBtn = id => item(id).querySelector(".custom-binding-stop");
const errorLine = () => byId("custom-bindings-error");
const isActive = id => item(id).classList.contains("is-active");
const isHidden = el => el.classList.contains("hidden");

function form(binding) {
    const actions = binding
        ? `<mdui-button class="custom-binding-activate${binding.active ? " hidden" : ""}"></mdui-button>
           <mdui-button class="custom-binding-stop${binding.active ? "" : " hidden"}"></mdui-button>
           <mdui-button class="custom-binding-save"></mdui-button>
           <mdui-button class="custom-binding-delete"></mdui-button>`
        : `<mdui-button class="custom-binding-save"></mdui-button>`;
    const on = (list, value) => (list?.includes(value) ? " checked" : "");
    return `
        <input class="custom-binding-name" type="text" value="${binding?.name ?? ""}"/>
        <input type="text" class="custom-binding-game-input" value="${binding?.gameName ?? ""}"/>
        <input type="hidden" class="custom-binding-game-id" value="${binding?.gameId ?? ""}"/>
        <mdui-list class="custom-binding-game-results"></mdui-list>
        <mdui-switch class="custom-binding-ccl-checkbox" value="Violence"${on(binding?.ccls, "Violence")}></mdui-switch>
        <mdui-switch class="custom-binding-ccl-checkbox" value="Drugs"${on(binding?.ccls, "Drugs")}></mdui-switch>
        <mdui-switch class="custom-binding-tw-checkbox" value="spoiler"${on(binding?.tws, "spoiler")}></mdui-switch>
        <mdui-switch class="custom-binding-uninterruptible"${binding?.uninterruptible ? " checked" : ""}></mdui-switch>
        ${actions}`;
}

function overview({ empty = false } = {}) {
    const rows = empty ? "" : `
        <mdui-collapse-item class="custom-binding-item is-active" data-custom-binding-id="${ART}" value="${ART}">
            <span class="custom-binding-active-chip"></span>
            ${form({ name: "Art", gameId: "509660", gameName: "Art", ccls: ["Violence"], tws: ["spoiler"], active: true })}
        </mdui-collapse-item>
        <mdui-collapse-item class="custom-binding-item" data-custom-binding-id="${DEV}" value="${DEV}">
            <span class="custom-binding-active-chip hidden"></span>
            ${form({ name: "Développement", gameId: "1469308723", gameName: "Software and Game Development",
                     uninterruptible: true })}
        </mdui-collapse-item>`;
    html(`
        <mdui-button id="custom-binding-new-btn"></mdui-button>
        <mdui-list id="channel-custom-bindings-list"
                   data-error-twitch-unavailable="Reconnecte ton compte Twitch"
                   data-error-bot-disabled="Catapult est en pause sur cette chaîne"
                   data-error-twitch-failed="Twitch n'a pas appliqué la catégorie"
                   data-error-name-taken="Nom déjà pris" data-error-limit-reached="20 bindings maximum"
                   data-error-invalid="Binding invalide">
            <mdui-collapse accordion>
                ${rows}
                <mdui-collapse-item class="custom-binding-item custom-binding-new hidden" value="new">
                    ${form(null)}
                </mdui-collapse-item>
            </mdui-collapse>
            <mdui-list-item id="channel-custom-bindings-empty"${empty ? "" : ' class="hidden"'}></mdui-list-item>
        </mdui-list>
        <p id="custom-bindings-error" class="hidden"></p>
        <mdui-dialog id="custom-binding-delete-dialog">
            <mdui-button class="custom-binding-delete-dismiss"></mdui-button>
            <mdui-button class="custom-binding-delete-confirm"></mdui-button>
        </mdui-dialog>`);
    // mdui-switch exposes its value/checked attributes as properties; jsdom knows no such element.
    document.querySelectorAll("mdui-switch").forEach(sw => {
        sw.value = sw.getAttribute("value");
        sw.checked = sw.hasAttribute("checked");
    });
    render();
}

/** The GameSearch handle attached to a row's category field. */
function gameOf(row) {
    const input = row.querySelector(".custom-binding-game-input");
    const index = window.GameSearch.attach.mock.calls.findLastIndex(call => call[0] === input);
    return window.GameSearch.attach.mock.results[index].value;
}

function tokenCard(example = "Art") {
    html(`
        <mdui-card id="channel-api-token-card" data-example-name="${example}"
                   data-label-none="Aucun jeton" data-label-since="Généré le {0}"
                   data-label-generate="Générer" data-label-regenerate="Régénérer" data-label-copied="Copié">
            <span id="api-token-status"></span>
            <mdui-button id="api-token-generate-btn"></mdui-button>
            <mdui-button id="api-token-revoke-btn" class="hidden"></mdui-button>
            <div id="api-token-reveal" class="hidden">
                <input id="api-token-value" readonly/>
                <mdui-button id="api-token-copy-btn"></mdui-button>
                <pre id="api-token-curl"></pre>
            </div>
            <mdui-dialog id="api-token-confirm-dialog">
                <mdui-button class="api-token-confirm-dismiss"></mdui-button>
                <mdui-button class="api-token-confirm-ok"></mdui-button>
            </mdui-dialog>
        </mdui-card>`);
    render();
}

beforeEach(async () => {
    history.replaceState(null, "", "/channel/enimaloc");
    postJson = vi.fn(async () => response(204));
    showError = vi.fn();
    refresh = vi.fn(async () => {});
    pick = null;
    window.CatapultCsrf = { postJson };
    window.CatapultChannel = {
        baseUrl: () => "/channel/enimaloc", refresh, showError,
        actionFailed: status => `failed ${status}`, unreachable: () => "unreachable",
        checkedValues: (root, selector) => Array.from(root.querySelectorAll(selector)).filter(s => s.checked).map(s => s.value)
    };
    window.GameSearch = {
        attach: vi.fn((input, results, url, onSelect, options) => {
            pick = onSelect;
            let selected = options?.initial?.id ? options.initial : null;
            return {
                selected: () => selected,
                select: game => { selected = game; input.value = game?.name ?? ""; },
                pick: game => { selected = game; input.value = game.name; onSelect(game); }
            };
        })
    };
    vi.stubGlobal("fetch", vi.fn(async () => response(200, { exists: false, createdAt: null })));
    await load("channel-custom-bindings");
});

describe("overview list: activation", () => {
    it("activates a binding by its id and marks it active", async () => {
        overview();
        activateBtn(DEV).click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(`/channel/enimaloc/custom-bindings/${DEV}/activate`, {});
        expect(isActive(DEV)).toBe(true);
        expect(isActive(ART)).toBe(false);
        expect(isHidden(item(DEV).querySelector(".custom-binding-active-chip"))).toBe(false);
        expect(isHidden(item(ART).querySelector(".custom-binding-active-chip"))).toBe(true);
        expect(isHidden(stopBtn(DEV))).toBe(false);
        expect(isHidden(activateBtn(DEV))).toBe(true);
        expect(isHidden(stopBtn(ART))).toBe(true);
        expect(isHidden(activateBtn(ART))).toBe(false);
        expect(isHidden(errorLine())).toBe(true);
    });

    it("deactivates through the stop button", async () => {
        overview();
        stopBtn(ART).click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/custom-bindings/deactivate", {});
        expect(document.querySelector(".custom-binding-item.is-active")).toBeNull();
        expect(isHidden(activateBtn(ART))).toBe(false);
    });

    it.each([
        ["twitch_unavailable", 409, "Reconnecte ton compte Twitch"],
        ["bot_disabled", 409, "Catapult est en pause sur cette chaîne"],
        ["twitch_failed", 502, "Twitch n'a pas appliqué la catégorie"],
    ])("tells the %s refusal in its own text and keeps the previous active binding", async (code, status, text) => {
        overview();
        postJson.mockResolvedValueOnce(response(status, { code }));
        activateBtn(DEV).click();
        await flush();

        expect(errorLine().textContent).toBe(text);
        expect(isHidden(errorLine())).toBe(false);
        expect(isActive(ART)).toBe(true);
    });

    it("falls back to the generic failure on a bare 403 or an unreachable server", async () => {
        overview();
        postJson.mockResolvedValueOnce(response(403, ""));
        activateBtn(DEV).click();
        await flush();
        expect(errorLine().textContent).toBe("failed 403");

        postJson.mockRejectedValueOnce(new TypeError("offline"));
        activateBtn(DEV).click();
        await flush();
        expect(errorLine().textContent).toBe("unreachable");
    });

    it("follows the activation made elsewhere (CustomBindingChangedEvent) and clears the error", async () => {
        overview();
        postJson.mockResolvedValueOnce(response(409, { code: "bot_disabled" }));
        activateBtn(DEV).click();
        await flush();

        CatapultCustomBindings.setActive(DEV);
        expect(isActive(DEV)).toBe(true);
        expect(isHidden(errorLine())).toBe(true);

        CatapultCustomBindings.setActive(null);
        expect(document.querySelector(".custom-binding-item.is-active")).toBeNull();
    });

    it("mirrors a row's open/close as .is-open", () => {
        overview();
        item(DEV).dispatchEvent(new Event("open"));
        expect(item(DEV).classList.contains("is-open")).toBe(true);
        item(DEV).dispatchEvent(new Event("close"));
        expect(item(DEV).classList.contains("is-open")).toBe(false);
    });
});

describe("overview list: create, edit, delete", () => {
    it("reveals and opens the empty row on New, hiding the empty notice", () => {
        overview({ empty: true });
        expect(isHidden(fresh())).toBe(true);

        byId("custom-binding-new-btn").click();

        expect(isHidden(fresh())).toBe(false);
        expect(fresh().classList.contains("is-open")).toBe(true);
        expect(document.querySelector("mdui-collapse").value).toBe("new");
        expect(isHidden(byId("channel-custom-bindings-empty"))).toBe(true);
    });

    it("creates a binding from the New row", async () => {
        overview();
        byId("custom-binding-new-btn").click();
        fresh().querySelector(".custom-binding-name").value = "  Musique ";
        gameOf(fresh()).pick({ id: "26936", name: "Music" });
        fresh().querySelectorAll(".custom-binding-ccl-checkbox")[1].checked = true;
        fresh().querySelector(".custom-binding-uninterruptible").checked = true;
        postJson.mockResolvedValueOnce(response(201, {}));

        fresh().querySelector(".custom-binding-save").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/custom-bindings", {
            name: "Musique", twitchGameId: "26936", twitchGameName: "Music",
            ccls: ["Drugs"], tws: [], uninterruptible: true
        });
        expect(fresh().querySelector(".custom-binding-game-id").value).toBe("26936");
        expect(refresh).toHaveBeenCalled();
    });

    it("saves a row's form to its id, with the values it was rendered with", async () => {
        overview();
        item(ART).querySelector(".custom-binding-name").value = "Dessin";
        postJson.mockResolvedValueOnce(response(200, { binding: {}, reapplyOutcome: null }));

        item(ART).querySelector(".custom-binding-save").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(`/channel/enimaloc/custom-bindings/${ART}`, {
            name: "Dessin", twitchGameId: "509660", twitchGameName: "Art",
            ccls: ["Violence"], tws: ["spoiler"], uninterruptible: false
        });
        expect(refresh).toHaveBeenCalled();
    });

    it("saves only the row's own switches", async () => {
        overview();
        postJson.mockResolvedValueOnce(response(200, { binding: {}, reapplyOutcome: "applied" }));

        item(DEV).querySelector(".custom-binding-save").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(`/channel/enimaloc/custom-bindings/${DEV}`, {
            name: "Développement", twitchGameId: "1469308723", twitchGameName: "Software and Game Development",
            ccls: [], tws: [], uninterruptible: true
        });
        expect(showError).not.toHaveBeenCalled();
    });

    it("tells when the saved active binding could not be re-applied", async () => {
        overview();
        postJson.mockResolvedValueOnce(response(200, { binding: {}, reapplyOutcome: "twitch_failed" }));
        item(ART).querySelector(".custom-binding-save").click();
        await flush();

        expect(showError).toHaveBeenCalledWith("Twitch n'a pas appliqué la catégorie");
        expect(refresh).toHaveBeenCalled();
    });

    it.each([
        ["name_taken", "Nom déjà pris"],
        ["limit_reached", "20 bindings maximum"],
        ["invalid", "Binding invalide"],
    ])("tells the %s refusal and does not re-render", async (code, text) => {
        overview();
        byId("custom-binding-new-btn").click();
        fresh().querySelector(".custom-binding-name").value = "Art";
        gameOf(fresh()).pick({ id: "509660", name: "Art" });
        postJson.mockResolvedValueOnce(response(409, { code }));
        fresh().querySelector(".custom-binding-save").click();
        await flush();

        expect(showError).toHaveBeenCalledWith(text);
        expect(refresh).not.toHaveBeenCalled();
    });

    it("does not post a nameless or categoryless binding", async () => {
        overview();
        fresh().querySelector(".custom-binding-save").click();
        await flush();
        item(ART).querySelector(".custom-binding-name").value = "  ";
        item(ART).querySelector(".custom-binding-save").click();
        await flush();

        expect(postJson).not.toHaveBeenCalled();
        expect(showError).toHaveBeenNthCalledWith(1, "Binding invalide");
        expect(showError).toHaveBeenNthCalledWith(2, "Binding invalide");
    });

    it("deletes only after the confirmation", async () => {
        overview();
        item(DEV).querySelector(".custom-binding-delete").click();
        expect(byId("custom-binding-delete-dialog").open).toBe(true);
        expect(postJson).not.toHaveBeenCalled();

        document.querySelector(".custom-binding-delete-dismiss").click();
        expect(byId("custom-binding-delete-dialog").open).toBe(false);
        expect(postJson).not.toHaveBeenCalled();

        item(DEV).querySelector(".custom-binding-delete").click();
        document.querySelector(".custom-binding-delete-confirm").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(`/channel/enimaloc/custom-bindings/${DEV}/delete`, {});
        expect(byId("custom-binding-delete-dialog").open).toBe(false);
        expect(refresh).toHaveBeenCalled();
    });

    it("tells a refused deletion and does not re-render", async () => {
        overview();
        postJson.mockResolvedValueOnce(response(409, { code: "twitch_failed" }));
        item(ART).querySelector(".custom-binding-delete").click();
        document.querySelector(".custom-binding-delete-confirm").click();
        await flush();

        expect(showError).toHaveBeenCalledWith("Twitch n'a pas appliqué la catégorie");
        expect(refresh).not.toHaveBeenCalled();
    });

    it("does not fetch the token status from the overview", async () => {
        overview();
        await flush();
        expect(fetch).not.toHaveBeenCalledWith("/me/api-token", expect.anything());
    });
});

describe("personal API token card", () => {
    it("shows no token, and offers to generate", async () => {
        tokenCard();
        await flush();

        expect(fetch).toHaveBeenCalledWith("/me/api-token", expect.anything());
        expect(byId("api-token-status").textContent).toBe("Aucun jeton");
        expect(byId("api-token-generate-btn").textContent).toBe("Générer");
        expect(byId("api-token-revoke-btn").classList.contains("hidden")).toBe(true);
    });

    it("shows since when a token exists, and offers to regenerate and revoke", async () => {
        fetch.mockResolvedValueOnce(response(200, { exists: true, createdAt: "2026-10-20T10:00:00Z" }));
        tokenCard();
        await flush();

        expect(byId("api-token-status").textContent).toMatch(/^Généré le .*2026/);
        expect(byId("api-token-generate-btn").textContent).toBe("Régénérer");
        expect(byId("api-token-revoke-btn").classList.contains("hidden")).toBe(false);
    });

    it("asks for confirmation before regenerating, then shows the token once with the curl example", async () => {
        fetch.mockResolvedValueOnce(response(200, { exists: true, createdAt: "2026-10-20T10:00:00Z" }));
        tokenCard("Développement");
        await flush();

        byId("api-token-generate-btn").click();
        expect(byId("api-token-confirm-dialog").open).toBe(true);
        expect(postJson).not.toHaveBeenCalled();

        postJson.mockResolvedValueOnce(response(200, { token: "cat_SECRET", createdAt: "2026-10-24T10:00:00Z" }));
        document.querySelector(".api-token-confirm-ok").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/me/api-token", {});
        expect(byId("api-token-confirm-dialog").open).toBe(false);
        expect(byId("api-token-value").value).toBe("cat_SECRET");
        expect(byId("api-token-reveal").classList.contains("hidden")).toBe(false);
        expect(byId("api-token-curl").textContent).toBe(
            `curl -X POST -H "Authorization: Bearer cat_SECRET" ${window.location.origin}/api/v2/custom-bindings/D%C3%A9veloppement/activate`);
        expect(refresh).not.toHaveBeenCalled();
    });

    it("generates the first token without asking", async () => {
        tokenCard();
        await flush();
        postJson.mockResolvedValueOnce(response(200, { token: "cat_FIRST", createdAt: "2026-10-24T10:00:00Z" }));

        byId("api-token-generate-btn").click();
        await flush();

        expect(byId("api-token-confirm-dialog").open).not.toBe(true);
        expect(byId("api-token-value").value).toBe("cat_FIRST");
        expect(byId("api-token-generate-btn").textContent).toBe("Régénérer");
        expect(byId("api-token-revoke-btn").classList.contains("hidden")).toBe(false);
    });

    it("copies the token", async () => {
        tokenCard();
        await flush();
        postJson.mockResolvedValueOnce(response(200, { token: "cat_COPY", createdAt: "2026-10-24T10:00:00Z" }));
        byId("api-token-generate-btn").click();
        await flush();
        const writeText = vi.fn(async () => {});
        Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });

        byId("api-token-copy-btn").click();
        await flush();

        expect(writeText).toHaveBeenCalledWith("cat_COPY");
    });

    it("revokes after the confirmation and forgets the shown token", async () => {
        fetch.mockResolvedValueOnce(response(200, { exists: true, createdAt: "2026-10-20T10:00:00Z" }));
        tokenCard();
        await flush();

        byId("api-token-revoke-btn").click();
        expect(byId("api-token-confirm-dialog").open).toBe(true);
        document.querySelector(".api-token-confirm-ok").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/me/api-token/delete", {});
        expect(byId("api-token-status").textContent).toBe("Aucun jeton");
        expect(byId("api-token-value").value).toBe("");
        expect(byId("api-token-reveal").classList.contains("hidden")).toBe(true);
    });

    it("tells a refused generation and shows no token", async () => {
        tokenCard();
        await flush();
        postJson.mockResolvedValueOnce(response(403, ""));
        byId("api-token-generate-btn").click();
        await flush();

        expect(showError).toHaveBeenCalledWith("failed 403");
        expect(byId("api-token-reveal").classList.contains("hidden")).toBe(true);
    });
});
