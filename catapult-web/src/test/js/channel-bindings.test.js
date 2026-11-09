/*
 * An association's reset to default: asked for confirmation, posted, then the page drawn anew.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render } from "./helpers.js";

let postJson;
let refresh;

function page() {
    html(`
        <mdui-list id="channel-bindings-list">
            <mdui-collapse-item data-binding-id="b1">
                <mdui-button class="binding-reset-btn"></mdui-button>
            </mdui-collapse-item>
        </mdui-list>
        <mdui-dialog id="binding-reset-dialog">
            <mdui-button class="binding-reset-dismiss"></mdui-button>
            <mdui-button class="binding-reset-confirm"></mdui-button>
        </mdui-dialog>`);
    render();
}

const dialog = () => document.getElementById("binding-reset-dialog");
const click = selector => document.querySelector(selector).click();

beforeEach(async () => {
    postJson = vi.fn(async () => ({ ok: true, status: 204 }));
    refresh = vi.fn(async () => {});
    window.CatapultChannel = { baseUrl: () => "/channel/enimaloc", postJson, refresh };
    window.scrollTo = vi.fn();
    await load("channel-bindings");
});

describe("the reset to default", () => {
    it("asks first, and posts nothing when dismissed", async () => {
        page();

        click(".binding-reset-btn");
        expect(dialog().open).toBe(true);

        click(".binding-reset-dismiss");
        await flush();
        expect(dialog().open).toBe(false);
        expect(postJson).not.toHaveBeenCalled();
    });

    it("posts once confirmed and draws the page anew", async () => {
        page();

        click(".binding-reset-btn");
        click(".binding-reset-confirm");
        await flush();

        expect(postJson).toHaveBeenCalledWith("/channel/bindings/b1/reset", {});
        expect(refresh).toHaveBeenCalledTimes(1);
    });

    it("leaves the page as it is when refused", async () => {
        postJson.mockResolvedValue({ ok: false, status: 403 });
        page();

        click(".binding-reset-btn");
        click(".binding-reset-confirm");
        await flush();

        expect(refresh).not.toHaveBeenCalled();
    });
});
