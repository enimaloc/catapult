import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render, response } from "./helpers.js";

let postJson;

beforeEach(async () => {
    history.replaceState(null, "", "/channel/enimaloc");
    postJson = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    await load("channel-page");
});

describe("username / baseUrl", () => {
    it("derives the channel from /channel/{username}", () => {
        expect(CatapultChannel.username()).toBe("enimaloc");
        expect(CatapultChannel.baseUrl()).toBe("/channel/enimaloc");
    });

    it("is null anywhere else", () => {
        for (const path of ["/", "/channels", "/channel/", "/privacy/x"]) {
            history.replaceState(null, "", path);
            expect(CatapultChannel.username()).toBeNull();
        }
    });
});

describe("refresh / postJson / postAndRefresh", () => {
    it("re-renders the current path and query without pushing history", async () => {
        history.replaceState(null, "", "/channel/enimaloc?status=AUTO#settings");

        await CatapultChannel.refresh();

        expect(CatapultSpa.navigate).toHaveBeenCalledWith("channel/enimaloc?status=AUTO", false);
    });

    it("posts through CatapultCsrf and returns the response", async () => {
        const result = await CatapultChannel.postJson("/x", { a: 1 });

        expect(postJson).toHaveBeenCalledWith("/x", { a: 1 });
        expect(result.status).toBe(204);
        expect(document.querySelector("mdui-snackbar")).toBeNull();
    });

    it("shows a snackbar when the mutation fails", async () => {
        postJson.mockResolvedValueOnce(response(403));

        await CatapultChannel.postJson("/x", {});

        const snackbar = document.querySelector("mdui-snackbar");
        expect(snackbar.textContent).toBe("Action failed (403)");
        expect(snackbar.open).toBe(true);
    });

    it("refreshes after posting", async () => {
        await CatapultChannel.postAndRefresh("/x", { a: 1 });

        expect(postJson).toHaveBeenCalledWith("/x", { a: 1 });
        expect(CatapultSpa.navigate).toHaveBeenCalledOnce();
    });
});

describe("on / checkedValues", () => {
    it("attaches the handler when the element exists and returns it", () => {
        html(`<button id="b"></button>`);
        const handler = vi.fn();

        const el = CatapultChannel.on("b", "click", handler);
        el.click();

        expect(el.id).toBe("b");
        expect(handler).toHaveBeenCalledOnce();
    });

    it("is a no-op returning null when the element is missing", () => {
        expect(CatapultChannel.on("missing", "click", vi.fn())).toBeNull();
    });

    it("reads checked state off the property, not :checked", () => {
        const root = html(`<i class="sw"></i><i class="sw"></i><i class="sw"></i><i class="other"></i>`);
        const switches = root.querySelectorAll("i");
        Object.assign(switches[0], { checked: true, value: "a" });
        Object.assign(switches[1], { checked: false, value: "b" });
        Object.assign(switches[2], { checked: true, value: "c" });
        Object.assign(switches[3], { checked: true, value: "d" });

        expect(CatapultChannel.checkedValues(root, ".sw")).toEqual(["a", "c"]);
    });
});

describe("bindings list helpers", () => {
    beforeEach(() => {
        html(`
            <ul id="channel-bindings-list">
                <mdui-collapse>
                    <li data-binding-id="b1"></li>
                    <li data-binding-id="b2"></li>
                </mdui-collapse>
                <li id="channel-bindings-empty" class="hidden"></li>
            </ul>`);
    });

    it("finds rows by binding id", () => {
        expect(CatapultChannel.getBindingsElements().map(el => el.dataset.bindingId)).toEqual(["b1", "b2"]);
        expect(CatapultChannel.getBindingElement("b2").dataset.bindingId).toBe("b2");
        expect(CatapultChannel.getBindingElement("nope")).toBeUndefined();
    });

    it("selects the current game in the accordion", () => {
        CatapultChannel.setCurrentGame("b2");

        expect(document.querySelector("mdui-collapse").value).toBe("b2");
    });

    it("removes a row and reveals the empty state once the last one is gone", () => {
        CatapultChannel.removeBinding("b1");
        expect(CatapultChannel.getBindingsElements()).toHaveLength(1);
        expect(document.getElementById("channel-bindings-empty").classList.contains("hidden")).toBe(true);

        CatapultChannel.removeBinding("b2");
        expect(document.querySelector("mdui-collapse")).toBeNull();
        expect(document.getElementById("channel-bindings-empty").classList.contains("hidden")).toBe(false);
    });

    it("ignores unknown rows", () => {
        CatapultChannel.removeBinding("nope");

        expect(CatapultChannel.getBindingsElements()).toHaveLength(2);
    });

    it("does nothing when the list has no accordion", () => {
        html(`<ul id="channel-bindings-list"><li id="channel-bindings-empty"></li></ul>`);

        expect(() => CatapultChannel.setCurrentGame("b1")).not.toThrow();
    });
});

describe("tab <-> location.hash", () => {
    function tabs(...values) {
        html(`<mdui-tabs id="channel-tabs">${values.map(v => `<mdui-tab value="${v}"></mdui-tab>`).join("")}</mdui-tabs>`);
        const el = document.getElementById("channel-tabs");
        el.value = "overview";
        return el;
    }

    it("restores the tab named by the hash", () => {
        const el = tabs("overview", "settings");
        history.replaceState(null, "", "/channel/enimaloc#settings");

        render();

        expect(el.value).toBe("settings");
    });

    it("ignores a hash naming a tab the viewer doesn't have", () => {
        const el = tabs("overview");
        history.replaceState(null, "", "/channel/enimaloc#settings");

        render();

        expect(el.value).toBe("overview");
    });

    it("mirrors tab changes into the hash, keeping the query", () => {
        const el = tabs("overview", "integrations");
        history.replaceState({ s: 1 }, "", "/channel/enimaloc?page=2");
        render();

        el.value = "integrations";
        el.dispatchEvent(new Event("change"));
        expect(location.search + location.hash).toBe("?page=2#integrations");
        expect(history.state).toEqual({ s: 1 });

        el.value = "overview";
        el.dispatchEvent(new Event("change"));
        expect(location.hash).toBe("");
    });

    it("does nothing on pages without tabs", () => {
        expect(() => render()).not.toThrow();
    });
});
