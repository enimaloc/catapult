/*
 * title-highlight.js: the stream title fields' variables, colored by a copy of the text laid over the input.
 */
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { flush, html, load } from "./helpers.js";

// A stand-in for mdui's text field: an input in an open shadow root, its value an accessor of the class.
beforeAll(() => {
    if (customElements.get("mdui-text-field")) return;
    customElements.define("mdui-text-field", class extends HTMLElement {
        constructor() {
            super();
            this.attachShadow({ mode: "open" }).innerHTML = "<input>";
            this.updateComplete = Promise.resolve(true);
        }
        get value() { return this.shadowRoot.querySelector("input").value; }
        set value(value) { this.shadowRoot.querySelector("input").value = value; }
    });
});

beforeEach(async () => {
    html("");
    await load("title-highlight");
});

const mirror = field => field.shadowRoot.querySelector(".title-highlight");
const variables = field => [...mirror(field).querySelectorAll(".title-highlight-variable")].map(v => v.textContent);

async function field(value, highlighted = true) {
    const el = document.createElement("mdui-text-field");
    if (highlighted) el.setAttribute("data-title-highlight", "");
    el.value = value;
    document.body.append(el);
    await flush();
    await flush();
    return el;
}

describe("segments", () => {
    it("cuts a title into its plain and variable parts", () => {
        expect(window.CatapultTitleHighlight.segments("Live {{GAME}} | {{ORIGINAL_TITLE}}{{TITLE}}!")).toEqual([
            { text: "Live ", variable: false },
            { text: "{{GAME}}", variable: true },
            { text: " | ", variable: false },
            { text: "{{ORIGINAL_TITLE}}", variable: true },
            { text: "{{TITLE}}", variable: true },
            { text: "!", variable: false }]);
    });

    it("leaves anything not written exactly as plain text", () => {
        expect(window.CatapultTitleHighlight.segments("{{game}} {{ GAME }} {GAME} {{STREAMER}}")).toEqual([
            { text: "{{game}} {{ GAME }} {GAME} {{STREAMER}}", variable: false }]);
        expect(window.CatapultTitleHighlight.segments("")).toEqual([]);
        expect(window.CatapultTitleHighlight.segments(null)).toEqual([]);
    });
});

describe("the field's copy", () => {
    it("lays the text over the input, only its variables colored, once in place", async () => {
        const el = await field("Live {{GAME}} {{game}}");

        expect(mirror(el).textContent).toBe("Live {{GAME}} {{game}}");
        expect(variables(el)).toEqual(["{{GAME}}"]);
        expect(mirror(el).getAttribute("aria-hidden")).toBe("true");
        expect(mirror(el).style.pointerEvents).toBe("none");
        expect(el.classList.contains("title-highlighted")).toBe(true);
    });

    it("follows the typing and a value set by a script", async () => {
        const el = await field("");

        el.shadowRoot.querySelector("input").value = "{{TITLE}} now";
        el.dispatchEvent(new Event("input"));
        expect(variables(el)).toEqual(["{{TITLE}}"]);

        el.value = "Back to {{ORIGINAL_TITLE}}";
        await flush();
        expect(mirror(el).textContent).toBe("Back to {{ORIGINAL_TITLE}}");
        expect(variables(el)).toEqual(["{{ORIGINAL_TITLE}}"]);
        expect(el.value).toBe("Back to {{ORIGINAL_TITLE}}");
    });

    it("dims the copy of a disabled field", async () => {
        const el = await field("{{GAME}}");

        el.disabled = true;
        el.setAttribute("disabled", "");
        await flush();

        expect(mirror(el).style.opacity).toBe("0.38");
    });

    it("leaves the other text fields alone", async () => {
        const el = await field("{{GAME}}", false);

        expect(mirror(el)).toBeNull();
        expect(el.classList.contains("title-highlighted")).toBe(false);
    });
});
