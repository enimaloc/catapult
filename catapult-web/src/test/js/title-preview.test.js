/*
 * title-preview.js: a title field's preview, the title it would send now, asked of catapult-api while typed.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, response } from "./helpers.js";

let posted;
const settle = async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve();
};

beforeEach(async () => {
    vi.useFakeTimers();
    history.replaceState(null, "", "/channel/enimaloc");
    posted = vi.fn(async (url, body) => response(200, { title: body.template ? `rendered: ${body.template}` : null }));
    window.CatapultCsrf = { postJson: posted };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    html("");
    await load("channel-page", "title-preview");
});

afterEach(() => vi.useRealTimers());

function field(value, preview = `data-preview-kind="BINDING" data-binding-id="b1"`) {
    html(`<div>
        <input class="title" value="${value}">
        <p class="title-preview" data-title-preview ${preview} data-none="(left as it is)">Preview:
          <span class="title-preview-text"></span></p>
      </div>`);
    return document.querySelector(".title");
}

const text = () => document.querySelector(".title-preview-text").textContent;

describe("title preview", () => {
    it("shows the field's title at once, asking with its kind and binding", async () => {
        field("Live {{GAME}}");
        await settle();

        expect(posted).toHaveBeenCalledWith("/channel/enimaloc/title/preview",
            { template: "Live {{GAME}}", kind: "BINDING", bindingId: "b1", customBindingId: null });
        expect(text()).toBe("rendered: Live {{GAME}}");
    });

    it("asks again once typing pauses, not at every key", async () => {
        const input = field("a");
        await settle();
        posted.mockClear();

        input.value = "ab";
        input.dispatchEvent(new Event("input", { bubbles: true }));
        input.value = "abc";
        input.dispatchEvent(new Event("input", { bubbles: true }));
        vi.advanceTimersByTime(399);
        expect(posted).not.toHaveBeenCalled();

        vi.advanceTimersByTime(1);
        await settle();
        expect(posted).toHaveBeenCalledTimes(1);
        expect(posted.mock.calls[0][1].template).toBe("abc");
        expect(text()).toBe("rendered: abc");
    });

    it("tells a title left as it is, and keeps quiet when the preview fails", async () => {
        field("", `data-preview-kind="CUSTOM" data-custom-binding-id="c1"`);
        await settle();
        expect(posted.mock.calls[0][1]).toEqual({ template: "", kind: "CUSTOM", bindingId: null, customBindingId: "c1" });
        expect(text()).toBe("(left as it is)");

        posted.mockImplementationOnce(async () => response(502));
        const input = document.querySelector(".title");
        input.value = "x";
        input.dispatchEvent(new Event("input", { bubbles: true }));
        vi.advanceTimersByTime(400);
        await settle();
        expect(text()).toBe("(left as it is)");
        expect(document.querySelector("mdui-snackbar")).toBeNull();
    });
});
