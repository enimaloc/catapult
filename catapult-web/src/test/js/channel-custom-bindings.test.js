/*
 * The custom bindings: the overview card's buttons (activate, deactivate, the error told per code), the
 * settings card (create, edit, confirmed delete) and the personal API token card (status, confirmed
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
const rowBtn = id => document.querySelector(`#channel-custom-bindings-card [data-custom-binding-id="${id}"] .custom-binding-trigger`);
const stopBtn = id => document.querySelector(`#channel-custom-bindings-card [data-custom-binding-id="${id}"] .custom-binding-stop`);
const errorLine = () => byId("custom-bindings-error");

function overview() {
    html(`
        <mdui-card id="channel-custom-bindings-card" data-error-twitch-unavailable="Reconnecte ton compte Twitch"
                   data-error-bot-disabled="Catapult est en pause sur cette chaîne"
                   data-error-twitch-failed="Twitch n'a pas appliqué la catégorie">
            <div data-custom-binding-id="${ART}" class="custom-binding-row is-active">
                <mdui-button class="custom-binding-trigger" variant="filled">Art</mdui-button>
                <mdui-button class="custom-binding-stop">Désactiver</mdui-button>
            </div>
            <div data-custom-binding-id="${DEV}" class="custom-binding-row">
                <mdui-button class="custom-binding-trigger" variant="tonal">Développement</mdui-button>
                <mdui-button class="custom-binding-stop hidden">Désactiver</mdui-button>
            </div>
            <p id="custom-bindings-error" class="hidden"></p>
        </mdui-card>`);
    render();
}

function settings() {
    html(`
        <mdui-card id="channel-custom-bindings-settings"
                   data-error-name-taken="Nom déjà pris" data-error-limit-reached="20 bindings maximum"
                   data-error-invalid="Binding invalide" data-error-twitch-failed="Twitch n'a pas appliqué la catégorie"
                   data-error-twitch-unavailable="Reconnecte ton compte Twitch" data-error-bot-disabled="Catapult est en pause">
            <div data-custom-binding-id="${ART}" data-name="Art" data-game-id="509660" data-game-name="Art"
                 data-ccls="Violence" data-tws="spoiler" data-uninterruptible="false" class="custom-binding-item">
                <mdui-button-icon class="custom-binding-edit"></mdui-button-icon>
                <mdui-button-icon class="custom-binding-delete"></mdui-button-icon>
            </div>
            <input id="custom-binding-name" type="text"/>
            <input type="text" id="custom-binding-game-input"/>
            <input type="hidden" id="custom-binding-game-id"/>
            <mdui-list id="custom-binding-game-results"></mdui-list>
            <mdui-switch class="custom-binding-ccl-checkbox" value="Violence"></mdui-switch>
            <mdui-switch class="custom-binding-ccl-checkbox" value="Drugs"></mdui-switch>
            <mdui-switch class="custom-binding-tw-checkbox" value="spoiler"></mdui-switch>
            <mdui-switch id="custom-binding-uninterruptible"></mdui-switch>
            <mdui-button id="custom-binding-save-btn">Save</mdui-button>
            <mdui-button id="custom-binding-cancel-btn" class="hidden">Cancel</mdui-button>
            <mdui-dialog id="custom-binding-delete-dialog">
                <mdui-button class="custom-binding-delete-dismiss"></mdui-button>
                <mdui-button class="custom-binding-delete-confirm"></mdui-button>
            </mdui-dialog>
        </mdui-card>`);
    // mdui-switch exposes its value attribute as a property; jsdom knows no such element.
    document.querySelectorAll("mdui-switch[value]").forEach(sw => { sw.value = sw.getAttribute("value"); });
    render();
}

function tokenCard(example = "Art") {
    html(`
        <mdui-card id="channel-api-token-card" data-example-name="${example}" data-api-base="https://API-HOST"
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

describe("overview card", () => {
    it("activates a binding by its id and marks it active", async () => {
        overview();
        rowBtn(DEV).click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(`/channel/enimaloc/custom-bindings/${DEV}/activate`, {});
        expect(document.querySelector(`[data-custom-binding-id="${DEV}"]`).classList.contains("is-active")).toBe(true);
        expect(document.querySelector(`[data-custom-binding-id="${ART}"]`).classList.contains("is-active")).toBe(false);
        expect(stopBtn(DEV).classList.contains("hidden")).toBe(false);
        expect(stopBtn(ART).classList.contains("hidden")).toBe(true);
        expect(rowBtn(DEV).getAttribute("variant")).toBe("filled");
        expect(rowBtn(ART).getAttribute("variant")).toBe("tonal");
        expect(errorLine().classList.contains("hidden")).toBe(true);
    });

    it("deactivates through the stop button", async () => {
        overview();
        stopBtn(ART).click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/custom-bindings/deactivate", {});
        expect(document.querySelector(".custom-binding-row.is-active")).toBeNull();
    });

    it.each([
        ["twitch_unavailable", 409, "Reconnecte ton compte Twitch"],
        ["bot_disabled", 409, "Catapult est en pause sur cette chaîne"],
        ["twitch_failed", 502, "Twitch n'a pas appliqué la catégorie"],
    ])("tells the %s refusal in its own text and keeps the previous active binding", async (code, status, text) => {
        overview();
        postJson.mockResolvedValueOnce(response(status, { code }));
        rowBtn(DEV).click();
        await flush();

        expect(errorLine().textContent).toBe(text);
        expect(errorLine().classList.contains("hidden")).toBe(false);
        expect(document.querySelector(`[data-custom-binding-id="${ART}"]`).classList.contains("is-active")).toBe(true);
    });

    it("falls back to the generic failure on a bare 403 or an unreachable server", async () => {
        overview();
        postJson.mockResolvedValueOnce(response(403, ""));
        rowBtn(DEV).click();
        await flush();
        expect(errorLine().textContent).toBe("failed 403");

        postJson.mockRejectedValueOnce(new TypeError("offline"));
        rowBtn(DEV).click();
        await flush();
        expect(errorLine().textContent).toBe("unreachable");
    });

    it("follows the activation made elsewhere (CustomBindingChangedEvent) and clears the error", async () => {
        overview();
        postJson.mockResolvedValueOnce(response(409, { code: "bot_disabled" }));
        rowBtn(DEV).click();
        await flush();

        CatapultCustomBindings.setActive(DEV);
        expect(document.querySelector(`[data-custom-binding-id="${DEV}"]`).classList.contains("is-active")).toBe(true);
        expect(errorLine().classList.contains("hidden")).toBe(true);

        CatapultCustomBindings.setActive(null);
        expect(document.querySelector(".custom-binding-row.is-active")).toBeNull();
    });
});

describe("settings card", () => {
    const val = id => byId(id).value;

    it("creates a binding from the form", async () => {
        settings();
        byId("custom-binding-name").value = "  Musique ";
        byId("custom-binding-game-id").value = "";
        window.GameSearch.attach.mock.results[0].value.pick({ id: "26936", name: "Music" });
        document.querySelectorAll(".custom-binding-ccl-checkbox")[1].checked = true;
        byId("custom-binding-uninterruptible").checked = true;
        postJson.mockResolvedValueOnce(response(201, {}));

        byId("custom-binding-save-btn").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/enimaloc/custom-bindings", {
            name: "Musique", twitchGameId: "26936", twitchGameName: "Music",
            ccls: ["Drugs"], tws: [], uninterruptible: true
        });
        expect(refresh).toHaveBeenCalled();
    });

    it("loads a binding into the form to edit it, then posts to its id", async () => {
        settings();
        document.querySelector(".custom-binding-edit").click();

        expect(val("custom-binding-name")).toBe("Art");
        expect(val("custom-binding-game-id")).toBe("509660");
        expect(val("custom-binding-game-input")).toBe("Art");
        expect(document.querySelector('.custom-binding-ccl-checkbox[value="Violence"]').checked).toBe(true);
        expect(document.querySelector('.custom-binding-ccl-checkbox[value="Drugs"]').checked).toBe(false);
        expect(document.querySelector(".custom-binding-tw-checkbox").checked).toBe(true);
        expect(byId("custom-binding-cancel-btn").classList.contains("hidden")).toBe(false);

        byId("custom-binding-name").value = "Dessin";
        postJson.mockResolvedValueOnce(response(200, { binding: {}, reapplyOutcome: null }));
        byId("custom-binding-save-btn").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(`/channel/enimaloc/custom-bindings/${ART}`, {
            name: "Dessin", twitchGameId: "509660", twitchGameName: "Art",
            ccls: ["Violence"], tws: ["spoiler"], uninterruptible: false
        });
        expect(refresh).toHaveBeenCalled();
    });

    it("tells when the saved active binding could not be re-applied", async () => {
        settings();
        document.querySelector(".custom-binding-edit").click();
        postJson.mockResolvedValueOnce(response(200, { binding: {}, reapplyOutcome: "twitch_failed" }));
        byId("custom-binding-save-btn").click();
        await flush();

        expect(showError).toHaveBeenCalledWith("Twitch n'a pas appliqué la catégorie");
        expect(refresh).toHaveBeenCalled();
    });

    it.each([
        ["name_taken", "Nom déjà pris"],
        ["limit_reached", "20 bindings maximum"],
        ["invalid", "Binding invalide"],
    ])("tells the %s refusal and does not re-render", async (code, text) => {
        settings();
        byId("custom-binding-name").value = "Art";
        window.GameSearch.attach.mock.results[0].value.pick({ id: "509660", name: "Art" });
        postJson.mockResolvedValueOnce(response(409, { code }));
        byId("custom-binding-save-btn").click();
        await flush();

        expect(showError).toHaveBeenCalledWith(text);
        expect(refresh).not.toHaveBeenCalled();
    });

    it("does not post a nameless or categoryless binding", async () => {
        settings();
        byId("custom-binding-save-btn").click();
        await flush();

        expect(postJson).not.toHaveBeenCalled();
        expect(showError).toHaveBeenCalledWith("Binding invalide");
    });

    it("deletes only after the confirmation", async () => {
        settings();
        document.querySelector(".custom-binding-delete").click();
        expect(byId("custom-binding-delete-dialog").open).toBe(true);
        expect(postJson).not.toHaveBeenCalled();

        document.querySelector(".custom-binding-delete-dismiss").click();
        expect(byId("custom-binding-delete-dialog").open).toBe(false);
        expect(postJson).not.toHaveBeenCalled();

        document.querySelector(".custom-binding-delete").click();
        document.querySelector(".custom-binding-delete-confirm").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith(`/channel/enimaloc/custom-bindings/${ART}/delete`, {});
        expect(byId("custom-binding-delete-dialog").open).toBe(false);
        expect(refresh).toHaveBeenCalled();
    });

    it("empties the form when the edit is cancelled", () => {
        settings();
        document.querySelector(".custom-binding-edit").click();
        byId("custom-binding-cancel-btn").click();

        expect(val("custom-binding-name")).toBe("");
        expect(document.querySelector(".custom-binding-tw-checkbox").checked).toBe(false);
        expect(byId("custom-binding-cancel-btn").classList.contains("hidden")).toBe(true);
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
            'curl -X POST -H "Authorization: Bearer cat_SECRET" https://API-HOST/api/v2/custom-bindings/D%C3%A9veloppement/activate');
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
