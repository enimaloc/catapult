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
        expect(window.CatapultTitleHighlight.segments("Live {{GAME}} | {{CATEGORY}} | {{ORIGINAL_TITLE}}{{TITLE}}!")).toEqual([
            { text: "Live ", variable: false },
            { text: "{{GAME}}", variable: true },
            { text: " | ", variable: false },
            { text: "{{CATEGORY}}", variable: true },
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

    it("colors the drop variables too", () => {
        expect(window.CatapultTitleHighlight.segments("{{DROP_NAME}} until {{DROP_END}}")).toEqual([
            { text: "{{DROP_NAME}}", variable: true },
            { text: " until ", variable: false },
            { text: "{{DROP_END}}", variable: true }]);
    });

    it("marks a ternary's syntax, its branches' variables colored as anywhere", () => {
        expect(window.CatapultTitleHighlight.segments("{{DROP_ENABLED ? [[[Drop]]] : [[]]}} {{GAME}} | !catapult")).toEqual([
            { text: "{{DROP_ENABLED ? [[", variable: false, syntax: true },
            { text: "[Drop]", variable: false },
            { text: "]] : [[", variable: false, syntax: true },
            { text: "]]}}", variable: false, syntax: true },
            { text: " ", variable: false },
            { text: "{{GAME}}", variable: true },
            { text: " | !catapult", variable: false }]);
    });

    it("reads the operators and a ternary nested in a branch", () => {
        expect(window.CatapultTitleHighlight.segments("{{!HAS_GAME || (IS_CUSTOM && HAS_TW) ? [[{{IS_MATURE ? [[18+ ]] : [[]]}}{{GAME}}]] : [[x]]}}")).toEqual([
            { text: "{{!HAS_GAME || (IS_CUSTOM && HAS_TW) ? [[", variable: false, syntax: true },
            { text: "{{IS_MATURE ? [[", variable: false, syntax: true },
            { text: "18+ ", variable: false },
            { text: "]] : [[", variable: false, syntax: true },
            { text: "]]}}", variable: false, syntax: true },
            { text: "{{GAME}}", variable: true },
            { text: "]] : [[", variable: false, syntax: true },
            { text: "x", variable: false },
            { text: "]]}}", variable: false, syntax: true }]);
    });

    it("leaves a ternary the server won't read as plain text, as it will be sent", () => {
        for (const raw of ["{{DROPS ? [[a]] : [[b]]}}", "{{DROP_ENABLED ? [[a]]}}", "{{DROP_ENABLED ? [[a]] : [[b]]",
            "{{DROP_ENABLED && ? [[a]] : [[b]]}}"]) {
            expect(window.CatapultTitleHighlight.segments(raw)).toEqual([{ text: raw, variable: false }]);
        }
        expect(window.CatapultTitleHighlight.segments("{{HAS_GAME ? [[a]] : [[b]]}} {{GAME}}").at(-1))
            .toEqual({ text: "{{GAME}}", variable: true });
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

    it("colors a ternary's syntax apart from its variables", async () => {
        const el = await field("{{DROP_ENABLED ? [[[Drop]]] : [[]]}} {{GAME}}");

        expect(mirror(el).textContent).toBe("{{DROP_ENABLED ? [[[Drop]]] : [[]]}} {{GAME}}");
        expect([...mirror(el).querySelectorAll(".title-highlight-syntax")].map(s => s.textContent))
            .toEqual(["{{DROP_ENABLED ? [[", "]] : [[", "]]}}"]);
        expect(variables(el)).toEqual(["{{GAME}}"]);
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
