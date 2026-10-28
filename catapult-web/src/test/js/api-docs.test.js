/*
 * api-docs.js: the API documentation page (/docs/api). Swagger UI is started on the site's copy of the spec, its
 * bundle loaded on the first render that shows the page; the indicator tells whether catapult-api answers.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

const BUNDLE = "/_static/swagger-ui/5.34.0/swagger-ui-bundle.js";
const CSS = "/_static/swagger-ui/5.34.0/swagger-ui.css";

function page({ withUi = true } = {}) {
    html(`
        <span id="api-docs-status" data-state="unknown" data-label-up="API up" data-label-down="API down">Checking</span>
        ${withUi ? `<div id="swagger-ui" data-spec="/docs/api/openapi.json" data-bundle="${BUNDLE}" data-css="${CSS}"
                         data-label-failed="Couldn't load"></div>` : ""}`);
}

function swaggerStub() {
    const bundle = vi.fn();
    bundle.presets = { apis: "apis-preset" };
    return bundle;
}

const ui = () => document.getElementById("swagger-ui");
const status = () => document.getElementById("api-docs-status");

describe("API documentation page", () => {
    beforeEach(() => {
        localStorage.clear();
        delete window.SwaggerUIBundle;
        vi.stubGlobal("fetch", vi.fn(async () => response(200, { status: "UP" })));
    });

    it("starts Swagger UI on the site's copy of the spec", async () => {
        window.SwaggerUIBundle = swaggerStub();
        page();
        await load("api-docs");

        render();
        await flush();

        expect(window.SwaggerUIBundle).toHaveBeenCalledTimes(1);
        const options = window.SwaggerUIBundle.mock.calls[0][0];
        expect(options.url).toBe("/docs/api/openapi.json");
        expect(options.domNode).toBe(ui());
        expect(options.presets).toEqual(["apis-preset"]);
        // Its hash history would reach spa.js's popstate, which would render the page again.
        expect(options.deepLinking).toBe(false);
    });

    it("starts it once per page shown, however many renders follow", async () => {
        window.SwaggerUIBundle = swaggerStub();
        page();
        await load("api-docs");

        render();
        render();
        await flush();

        expect(window.SwaggerUIBundle).toHaveBeenCalledTimes(1);
    });

    it("does nothing on another page", async () => {
        window.SwaggerUIBundle = swaggerStub();
        html("<p>elsewhere</p>");
        await load("api-docs");

        render();
        await flush();

        expect(window.SwaggerUIBundle).not.toHaveBeenCalled();
        expect(fetch).not.toHaveBeenCalled();
    });

    it("loads Swagger UI's bundle and stylesheet the first time, then starts it", async () => {
        page();
        await load("api-docs");

        render();
        const script = document.head.querySelector(`script[src="${BUNDLE}"]`);
        expect(script).not.toBeNull();
        expect(document.head.querySelector(`link[rel="stylesheet"][href="${CSS}"]`)).not.toBeNull();

        window.SwaggerUIBundle = swaggerStub();
        script.dispatchEvent(new Event("load"));
        await flush();

        expect(window.SwaggerUIBundle).toHaveBeenCalledTimes(1);
    });

    it("loads the bundle once, even when the page is shown again before it came", async () => {
        page();
        await load("api-docs");
        render();
        page();
        render();

        expect(document.head.querySelectorAll(`script[src="${BUNDLE}"]`)).toHaveLength(1);
        expect(document.head.querySelectorAll(`link[href="${CSS}"]`)).toHaveLength(1);
    });

    it("says so when the bundle can't be loaded, and tries again on the next page shown", async () => {
        page();
        await load("api-docs");

        render();
        document.head.querySelector("script").dispatchEvent(new Event("error"));
        await flush();
        expect(ui().textContent).toBe("Couldn't load");

        page();
        render();
        expect(document.head.querySelectorAll(`script[src="${BUNDLE}"]`)).toHaveLength(2);
    });

    describe("cached copy banner", () => {
        function banner(attributes) {
            html(`<p id="api-docs-cached" ${attributes}>API unreachable: documentation cached, from 27/10 09:30.</p>`);
        }
        const text = () => document.getElementById("api-docs-cached").textContent;

        it("tells when the copy was fetched in the visitor's own time zone", async () => {
            banner('data-fetched-at="2026-03-04T05:06:00Z" data-label="Cached, from {0}."');
            await load("api-docs");

            render();

            const at = new Date("2026-03-04T05:06:00Z");
            const two = n => String(n).padStart(2, "0");
            expect(text()).toBe(`Cached, from ${two(at.getDate())}/${two(at.getMonth() + 1)} `
                + `${two(at.getHours())}:${two(at.getMinutes())}.`);
            expect(text()).toMatch(/^Cached, from \d\d\/\d\d \d\d:\d\d\.$/);
        });

        it("keeps the server's words without a readable instant", async () => {
            banner('data-fetched-at="nope" data-label="Cached, from {0}."');
            await load("api-docs");

            render();

            expect(text()).toBe("API unreachable: documentation cached, from 27/10 09:30.");
        });

        it("keeps the server's words without a label", async () => {
            banner('data-fetched-at="2026-03-04T05:06:00Z"');
            await load("api-docs");

            render();

            expect(text()).toBe("API unreachable: documentation cached, from 27/10 09:30.");
        });
    });

    describe("indicator", () => {
        it("stays undecided, as the server rendered it, until /api/health answers", async () => {
            let answer;
            fetch.mockReturnValue(new Promise(resolve => { answer = resolve; }));
            page({ withUi: false });
            await load("api-docs");

            render();
            await flush();
            expect(status().dataset.state).toBe("unknown");
            expect(status().textContent).toBe("Checking");

            answer(response(200, { status: "UP" }));
            await flush();
            expect(status().dataset.state).toBe("up");
        });

        it("asks the site's /api/health, never kept", async () => {
            window.SwaggerUIBundle = swaggerStub();
            page();
            await load("api-docs");

            render();
            await flush();

            expect(fetch).toHaveBeenCalledWith("/api/health", { cache: "no-store" });
            expect(status().dataset.state).toBe("up");
            expect(status().textContent).toBe("API up");
        });

        it("turns down when catapult-api doesn't answer", async () => {
            fetch.mockResolvedValue(response(503, { status: "DOWN" }));
            page({ withUi: false });
            await load("api-docs");

            render();
            await flush();

            expect(status().dataset.state).toBe("down");
            expect(status().textContent).toBe("API down");
        });

        it("turns down when the site itself can't be reached", async () => {
            fetch.mockRejectedValue(new TypeError("offline"));
            page({ withUi: false });
            await load("api-docs");

            render();
            await flush();

            expect(status().dataset.state).toBe("down");
        });

        it("turns up again when catapult-api comes back", async () => {
            page({ withUi: false });
            status().dataset.state = "down";
            await load("api-docs");

            render();
            await flush();

            expect(status().dataset.state).toBe("up");
        });
    });

    describe("the keys card's widget UUID", () => {
        function widget() {
            html(`
                <div id="api-docs-widget" data-label-copied="UUID copied">
                    <input id="api-docs-widget-value" value="5f0c2a8e-9d3b-4c7a-8e21-6b4f1d2c9a70" readonly>
                    <button id="api-docs-widget-copy-btn"></button>
                </div>`);
        }

        it("copies the UUID and says so", async () => {
            const writeText = vi.fn(async () => {});
            vi.stubGlobal("navigator", { clipboard: { writeText } });
            const showError = vi.fn();
            window.CatapultChannel = { showError };
            widget();
            await load("api-docs");

            render();
            render();
            document.getElementById("api-docs-widget-copy-btn").click();
            await flush();

            expect(writeText).toHaveBeenCalledTimes(1);
            expect(writeText).toHaveBeenCalledWith("5f0c2a8e-9d3b-4c7a-8e21-6b4f1d2c9a70");
            expect(showError).toHaveBeenCalledWith("UUID copied");
        });

        it("selects the field when the clipboard refuses", async () => {
            vi.stubGlobal("navigator", { clipboard: { writeText: vi.fn(async () => { throw new Error("denied"); }) } });
            widget();
            const field = document.getElementById("api-docs-widget-value");
            field.select = vi.fn();
            await load("api-docs");

            render();
            document.getElementById("api-docs-widget-copy-btn").click();
            await flush();

            expect(field.select).toHaveBeenCalled();
        });
    });

    describe("a prefetched (pending) keys card", () => {
        const pending = `<mdui-card id="api-docs-keys-card" data-pending="true" data-src="/spa/docs/api"
                                   data-label-unavailable="Keys unreadable"><p>Your keys</p></mdui-card>`;
        const fresh = `<mdui-layout-main><mdui-card id="api-docs-keys-card">
                <div id="api-docs-widget" data-label-copied="UUID copied">
                    <input id="api-docs-widget-value" value="the-uuid" readonly>
                    <button id="api-docs-widget-copy-btn"></button>
                </div>
                <div id="api-docs-read-token"></div></mdui-card></mdui-layout-main>`;

        it("is replaced by the member's real card, fetched as a navigation, then wired", async () => {
            const attachAll = vi.fn();
            window.CatapultApiTokenCards = { attachAll };
            const writeText = vi.fn(async () => {});
            vi.stubGlobal("navigator", { clipboard: { writeText } });
            window.CatapultChannel = { showError: vi.fn() };
            fetch.mockImplementation(async url => (url === "/spa/docs/api" ? response(200, fresh) : response(200, { status: "UP" })));
            html(pending);
            await load("api-docs");

            render();
            await flush();

            expect(fetch).toHaveBeenCalledWith("/spa/docs/api");
            const card = document.getElementById("api-docs-keys-card");
            expect(card.dataset.pending).toBeUndefined();
            expect(document.getElementById("api-docs-read-token")).not.toBeNull();
            expect(attachAll).toHaveBeenCalledTimes(1);
            document.getElementById("api-docs-widget-copy-btn").click();
            await flush();
            expect(writeText).toHaveBeenCalledWith("the-uuid");
        });

        it("says the keys can't be read when that fetch fails", async () => {
            fetch.mockImplementation(async url => (url === "/spa/docs/api" ? response(502, "") : response(200, { status: "UP" })));
            html(pending);
            await load("api-docs");

            render();
            await flush();

            const card = document.getElementById("api-docs-keys-card");
            expect(card.dataset.pending).toBe("true");
            expect(card.textContent).toContain("Keys unreadable");
        });
    });
});
