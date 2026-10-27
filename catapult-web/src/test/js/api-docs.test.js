/*
 * api-docs.js: the API documentation page (/docs/api). Swagger UI is started on the site's copy of the spec, its
 * bundle loaded on the first render that shows the page; catapult-api's generic widget-token example is swapped for
 * the visitor's own; the indicator tells whether catapult-api answers.
 */
import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

const BUNDLE = "/_static/swagger-ui/5.34.0/swagger-ui-bundle.js";
const CSS = "/_static/swagger-ui/5.34.0/swagger-ui.css";

function page({ withUi = true } = {}) {
    html(`
        <span id="api-docs-status" data-state="up" data-label-up="API up" data-label-down="API down">API up</span>
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

    describe("widget token example", () => {
        const EXAMPLE = "00000000-0000-0000-0001-000000000190";

        it("is catapult-api's own generic example", () => {
            // Vitest runs in catapult-web, as Gradle's jsTest does.
            const snapshot = readFileSync("../catapult-api/src/test/resources/frozen-routes/api-docs.json", "utf8");
            expect(JSON.parse(snapshot).body).toContain(`"example":"${EXAMPLE}"`);
        });

        async function withInput(value) {
            window.SwaggerUIBundle = swaggerStub();
            page();
            await load("api-docs");
            render();
            await flush();
            const input = document.createElement("input");
            input.value = value;
            const events = [];
            input.addEventListener("input", () => events.push("input"));
            input.addEventListener("change", () => events.push("change"));
            ui().append(input);
            await flush();
            return { input, events };
        }

        it("is replaced by the visitor's own token once Try it out shows it", async () => {
            localStorage.setItem("catapult_widget_uuid", "my-own-token");

            const { input, events } = await withInput(EXAMPLE);

            expect(input.value).toBe("my-own-token");
            expect(events).toEqual(["input", "change"]);
        });

        it("leaves any other value alone", async () => {
            localStorage.setItem("catapult_widget_uuid", "my-own-token");

            const { input, events } = await withInput("typed by the visitor");

            expect(input.value).toBe("typed by the visitor");
            expect(events).toEqual([]);
        });

        it("stays when the visitor has no token", async () => {
            const { input } = await withInput(EXAMPLE);

            expect(input.value).toBe(EXAMPLE);
        });

        it("stays when the storage can't be read", async () => {
            vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
                throw new Error("denied");
            });

            const { input } = await withInput(EXAMPLE);

            expect(input.value).toBe(EXAMPLE);
        });
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
});
