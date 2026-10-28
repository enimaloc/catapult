import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load } from "./helpers.js";

beforeEach(async () => {
    await load("visibility", "lazy");
});

describe("show", () => {
    it("builds the template's content in its place and tells the page, its parent as the root", () => {
        html(`<section id="panel"><template data-lazy><p id="inside">x</p></template></section>`);
        const shown = vi.fn();
        document.addEventListener("catapult:lazy", shown, { once: true });

        const root = CatapultLazy.showIn(document.getElementById("panel"));

        expect(root.id).toBe("panel");
        expect(document.getElementById("inside")).not.toBeNull();
        expect(document.querySelector("template")).toBeNull();
        expect(shown.mock.calls[0][0].detail).toEqual({ root, stale: false });
    });

    it("tells no one when quiet, and nothing twice", () => {
        html(`<section id="panel"><template data-lazy><p>x</p></template></section>`);
        const shown = vi.fn();
        document.addEventListener("catapult:lazy", shown);

        CatapultLazy.showIn(document.getElementById("panel"), { quiet: true });

        expect(shown).not.toHaveBeenCalled();
        expect(CatapultLazy.showIn(document.getElementById("panel"))).toBeNull();
        document.removeEventListener("catapult:lazy", shown);
    });

    it("leaves a template that is not a lazy part alone", () => {
        html(`<section id="panel"><template id="row-template"><p>x</p></template></section>`);

        expect(CatapultLazy.showIn(document.getElementById("panel"))).toBeNull();
        expect(document.getElementById("row-template")).not.toBeNull();
    });
});

describe("touch", () => {
    it("marks stale the tabs waiting, never a collapse item's body", () => {
        html(`<mdui-tab-panel id="panel"><template data-lazy><p>x</p></template></mdui-tab-panel>
              <mdui-collapse-item data-binding-id="b1" id="row"><template data-lazy><p>y</p></template></mdui-collapse-item>`);
        const details = [];
        document.addEventListener("catapult:lazy", e => details.push(e.detail.stale));

        CatapultLazy.touch();
        CatapultLazy.showIn(document.getElementById("panel"));
        CatapultLazy.showIn(document.getElementById("row"));

        expect(details).toEqual([true, false]);
    });

    it("leaves a part shown after the event fresh", () => {
        html(`<section id="panel"><template data-lazy><p>x</p></template></section>`);
        CatapultLazy.showIn(document.getElementById("panel"), { quiet: true });

        expect(() => CatapultLazy.touch()).not.toThrow();
        expect(CatapultLazy.pending()).toEqual([]);
    });
});

describe("a collapse item", () => {
    it("builds its body as it opens, before its own listeners, and tells it", () => {
        html(`<mdui-collapse-item id="item"><template data-lazy><p id="body">x</p></template></mdui-collapse-item>`);
        const item = document.getElementById("item");
        let seen = null;
        item.addEventListener("open", () => { seen = document.getElementById("body"); });
        const told = vi.fn();
        item.addEventListener("catapult:lazy", told);

        item.dispatchEvent(new Event("open", { bubbles: true }));
        item.dispatchEvent(new Event("open", { bubbles: true }));

        expect(seen).not.toBeNull();
        expect(told).toHaveBeenCalledOnce();
    });
});
