import { vi } from "vitest";

const JS_DIR = "../../main/resources/static/js";

/** Evaluates the given scripts (file names without .js), in order, as the page would. */
export async function load(...names) {
    vi.resetModules();
    for (const name of names) {
        await import(`${JS_DIR}/${name}.js`);
    }
}

/** Replaces the document body with the given markup and returns it. */
export function html(markup) {
    document.body.innerHTML = markup;
    return document.body;
}

/** Fires the event spa.js dispatches after each (re-)render. */
export function render() {
    document.dispatchEvent(new CustomEvent("catapult:render"));
}

/**
 * A resolved fetch Response-like object.
 *
 * @param {number} status
 * @param {*} body a raw string, or any value served as JSON
 */
export function response(status = 200, body = "") {
    return {
        ok: status >= 200 && status < 300,
        status,
        text: async () => (typeof body === "string" ? body : JSON.stringify(body)),
        json: async () => (typeof body === "string" ? JSON.parse(body) : body),
    };
}

/** Lets pending promise callbacks (fetch().then chains, async handlers) run. */
export async function flush() {
    for (let i = 0; i < 5; i++) await Promise.resolve();
    await new Promise(resolve => setTimeout(resolve, 0));
}
