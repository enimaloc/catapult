/*
 * The personal API token cards (api-token-cards.js): the full token's (status, confirmed regeneration, the token shown
 * once with its curl example, copy, revocation) and the read-only token's, the same card on its own base URL, whose
 * generation is told to the page for the chat bots' links.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

let postJson;
let showError;
const byId = id => document.getElementById(id);

function card(id, prefix, attributes = "") {
    return `
        <mdui-card id="${id}" ${attributes}
                   data-label-none="Aucun jeton" data-label-since="Généré le {0}"
                   data-label-generate="Générer" data-label-regenerate="Régénérer" data-label-copied="Copié">
            <span id="${prefix}-status"></span>
            <mdui-button id="${prefix}-generate-btn"></mdui-button>
            <mdui-button id="${prefix}-revoke-btn" class="hidden"></mdui-button>
            <div id="${prefix}-reveal" class="hidden">
                <input id="${prefix}-value" readonly/>
                <mdui-button id="${prefix}-copy-btn"></mdui-button>
                <pre id="${prefix}-curl"></pre>
            </div>
            <mdui-dialog id="${prefix}-confirm-dialog">
                <mdui-button class="api-token-confirm-dismiss"></mdui-button>
                <mdui-button class="api-token-confirm-ok"></mdui-button>
            </mdui-dialog>
        </mdui-card>`;
}

function tokenCard(example = "Art") {
    html(card("channel-api-token-card", "api-token", `data-example-name="${example}"`));
    render();
}

function readTokenCard() {
    html(card("channel-read-token-card", "read-token"));
    render();
}

beforeEach(async () => {
    postJson = vi.fn(async () => response(204));
    showError = vi.fn();
    window.CatapultCsrf = { postJson };
    window.CatapultChannel = { showError, actionFailed: status => `failed ${status}`, unreachable: () => "unreachable" };
    vi.stubGlobal("fetch", vi.fn(async () => response(200, { exists: false, createdAt: null })));
    await load("api-token-cards");
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
        tokenCard("L'Art");
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
            "curl -X PUT -H 'Authorization: Bearer cat_SECRET' -H 'Content-Type: application/json' "
            + `-d '{"idOrName":"L'\\''Art"}' ${window.location.origin}/api/me/custom-bindings/active`);
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

describe("personal API token status, once per page", () => {
    const tokenFetches = () => fetch.mock.calls.filter(([url]) => url === "/me/api-token");

    it("token_isFetchedOncePerPageLoad", async () => {
        fetch.mockResolvedValue(response(200, { exists: true, createdAt: "2026-10-20T10:00:00Z" }));
        for (let i = 0; i < 3; i++) {
            tokenCard();
            await flush();
            expect(byId("api-token-status").textContent).toMatch(/^Généré le .*2026/);
        }

        expect(tokenFetches()).toHaveLength(1);
    });

    it("token_afterRegenerate_rendersWithoutRefetching", async () => {
        fetch.mockResolvedValue(response(200, { exists: false, createdAt: null }));
        tokenCard();
        await flush();
        postJson.mockResolvedValue(response(200, { token: "cat_NEW", createdAt: "2026-10-26T10:00:00Z" }));
        byId("api-token-generate-btn").click();
        await flush();

        tokenCard();
        await flush();

        expect(byId("api-token-status").textContent).toMatch(/^Généré le .*2026/);
        expect(byId("api-token-generate-btn").textContent).toBe("Régénérer");
        expect(tokenFetches()).toHaveLength(1);
    });

    it("token_afterRevoke_rendersWithoutRefetching", async () => {
        fetch.mockResolvedValue(response(200, { exists: true, createdAt: "2026-10-20T10:00:00Z" }));
        tokenCard();
        await flush();
        byId("api-token-revoke-btn").click();
        document.querySelector(".api-token-confirm-ok").click();
        await flush();

        tokenCard();
        await flush();

        expect(byId("api-token-status").textContent).toBe("Aucun jeton");
        expect(tokenFetches()).toHaveLength(1);
    });

    it("token_afterAFailedFetch_retriesOnTheNextRender", async () => {
        fetch.mockResolvedValueOnce(response(500));
        tokenCard();
        byId("channel-api-token-card").dataset.labelUnavailable = "Indisponible";
        await flush();
        expect(byId("api-token-status").textContent).toBe("Indisponible");

        fetch.mockResolvedValueOnce(response(200, { exists: true, createdAt: "2026-10-20T10:00:00Z" }));
        tokenCard();
        await flush();

        expect(byId("api-token-status").textContent).toMatch(/^Généré le .*2026/);
        expect(tokenFetches()).toHaveLength(2);
    });
});

describe("read-only token card", () => {
    it("reads its own status, apart from the full token's", async () => {
        html(card("channel-read-token-card", "read-token") + card("channel-api-token-card", "api-token", 'data-example-name="Art"'));
        fetch.mockImplementation(async url => response(200, url === "/me/api-token/read"
            ? { exists: true, createdAt: "2026-10-20T10:00:00Z" } : { exists: false, createdAt: null }));
        render();
        await flush();

        expect(fetch).toHaveBeenCalledWith("/me/api-token/read", expect.anything());
        expect(byId("read-token-status").textContent).toMatch(/^Généré le .*2026/);
        expect(byId("api-token-status").textContent).toBe("Aucun jeton");
    });

    it("generates on its own route, shows the summary link, and tells the page", async () => {
        const told = vi.fn();
        document.addEventListener("catapult:api-token", event => told(event.detail));
        readTokenCard();
        await flush();
        const created = { token: "catr_READ", createdAt: "2026-10-24T10:00:00Z",
            summaryUrl: "https://catapult.test/api/me/game/summary?token=catr_READ" };
        postJson.mockResolvedValueOnce(response(200, created));

        byId("read-token-generate-btn").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/me/api-token/read", {});
        expect(byId("read-token-value").value).toBe("catr_READ");
        expect(byId("read-token-curl").textContent).toBe("curl 'https://catapult.test/api/me/game/summary?token=catr_READ'");
        expect(told).toHaveBeenCalledWith({ base: "/me/api-token/read", created });
    });

    it("revokes on its own route, and tells the page", async () => {
        const told = vi.fn();
        document.addEventListener("catapult:api-token", event => told(event.detail));
        fetch.mockResolvedValue(response(200, { exists: true, createdAt: "2026-10-20T10:00:00Z" }));
        readTokenCard();
        await flush();

        byId("read-token-revoke-btn").click();
        document.querySelector("#read-token-confirm-dialog .api-token-confirm-ok").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/me/api-token/read/delete", {});
        expect(byId("read-token-status").textContent).toBe("Aucun jeton");
        expect(told).toHaveBeenCalledWith({ base: "/me/api-token/read", created: null });
    });
});

describe("the API docs page's keys card", () => {
    it("wires both token rows to their own routes, with their own examples", async () => {
        html(card("api-docs-read-token", "api-docs-read-token")
            + card("api-docs-full-token", "api-docs-full-token", 'data-example-name="Speedrun"'));
        render();
        await flush();

        expect(fetch).toHaveBeenCalledWith("/me/api-token/read", expect.anything());
        expect(fetch).toHaveBeenCalledWith("/me/api-token", expect.anything());
        expect(byId("api-docs-read-token-status").textContent).toBe("Aucun jeton");
        expect(byId("api-docs-full-token-status").textContent).toBe("Aucun jeton");

        postJson.mockResolvedValueOnce(response(200, { token: "cat_FULL", createdAt: "2026-10-28T10:00:00Z" }));
        byId("api-docs-full-token-generate-btn").click();
        await flush();

        expect(postJson).toHaveBeenCalledWith("/me/api-token", {});
        expect(byId("api-docs-full-token-value").value).toBe("cat_FULL");
        expect(byId("api-docs-full-token-curl").textContent).toContain('"idOrName":"Speedrun"');
        expect(byId("api-docs-read-token-value").value).toBe("");
    });

    it("has only the read-only row outside the custom bindings' experiment", async () => {
        html(card("api-docs-read-token", "api-docs-read-token"));
        render();
        await flush();

        expect(fetch).toHaveBeenCalledTimes(1);
        expect(fetch).toHaveBeenCalledWith("/me/api-token/read", expect.anything());
    });

    it("wires a card put in after the render, when asked", async () => {
        html(card("api-docs-read-token", "api-docs-read-token"));
        window.CatapultApiTokenCards.attachAll();
        await flush();

        expect(fetch).toHaveBeenCalledWith("/me/api-token/read", expect.anything());
        expect(byId("api-docs-read-token-status").textContent).toBe("Aucun jeton");
    });
});
