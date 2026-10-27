/*
 * The OBS card's widget link: Copy, Regenerate (asks first, posts once, then refreshes the page) and the
 * browser-source check / creation through the page's own OBS connection (CatapultObs, stubbed here).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

const URL = "https://catapult.test/widget/obs/11111111-2222-3333-4444-555555555555";

let postJson;
let refresh;
let release;
let writeText;
let showError;
let requests;
let connected;

const status = () => document.getElementById("obs-widget-source-status");
const missing = () => document.getElementById("obs-widget-source-missing");
const createBtn = () => document.getElementById("obs-widget-source-create-btn");
const dialog = () => document.getElementById("obs-widget-regenerate-dialog");
const confirm = () => dialog().querySelector(".obs-widget-regenerate-confirm");

function page() {
    html(`
        <div id="obs-widget" data-widget-url="${URL}" data-label-copied="copied" data-label-copy-failed="nocopy"
             data-label-refused="refused" data-label-unavailable="down" data-label-create-failed="nocreate">
            <mdui-text-field id="obs-widget-link" readonly value="${URL}"></mdui-text-field>
            <mdui-button id="obs-widget-copy-btn"></mdui-button>
            <mdui-button id="obs-widget-regenerate-btn"></mdui-button>
            <mdui-dialog id="obs-widget-regenerate-dialog">
                <mdui-button class="obs-widget-regenerate-dismiss"></mdui-button>
                <mdui-button class="obs-widget-regenerate-confirm"></mdui-button>
            </mdui-dialog>
            <p id="obs-widget-source-status" class="hidden" data-label-checking="checking" data-label-found="found" data-label-unreachable="unreachable"></p>
            <div id="obs-widget-source-missing" class="hidden"><mdui-button id="obs-widget-source-create-btn"></mdui-button></div>
        </div>`);
    render();
}

beforeEach(async () => {
    vi.useFakeTimers();
    connected = true;
    showError = vi.fn();
    postJson = vi.fn(() => new Promise(resolve => { release = () => resolve(response(204)); }));
    refresh = vi.fn(async () => {});
    writeText = vi.fn(async () => {});
    requests = {
        getInputList: vi.fn(async () => ({ inputs: [] })),
        getInputSettings: vi.fn(async () => ({ inputSettings: {} })),
        getCurrentProgramScene: vi.fn(async () => ({ currentProgramSceneName: "Main" })),
        createInput: vi.fn(async () => ({})),
    };
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    window.CatapultChannel = { baseUrl: () => "/channel/me", refresh, showError };
    window.CatapultCsrf = { postJson };
    window.CatapultObs = { isConnected: () => connected, requests };
    await load("channel-obs-widget");
});

afterEach(() => vi.useRealTimers());

describe("channel-obs-widget link", () => {
    it("copies the link; asks before regenerating, then refreshes", async () => {
        page();

        document.getElementById("obs-widget-copy-btn").click();
        await vi.advanceTimersByTimeAsync(0);
        expect(writeText).toHaveBeenCalledWith(URL);
        expect(showError).toHaveBeenCalledWith("copied");

        // Asked first: nothing is posted until the dialog is confirmed.
        document.getElementById("obs-widget-regenerate-btn").click();
        expect(dialog().open).toBe(true);
        expect(postJson).not.toHaveBeenCalled();

        confirm().click();
        confirm().click(); // a double click posts once
        expect(postJson).toHaveBeenCalledTimes(1);
        expect(postJson).toHaveBeenCalledWith("/channel/me/settings/twitchat/regenerate", {});
        expect(confirm().disabled).toBe(true);

        release();
        await vi.advanceTimersByTimeAsync(0);
        expect(refresh).toHaveBeenCalledTimes(1);
    });

    it("says why when copying or regenerating fails, and frees the button", async () => {
        writeText.mockRejectedValue(new Error("denied"));
        page();
        document.getElementById("obs-widget-copy-btn").click();
        await vi.advanceTimersByTimeAsync(0);
        expect(showError).toHaveBeenCalledWith("nocopy");

        postJson.mockResolvedValue(response(403));
        document.getElementById("obs-widget-regenerate-btn").click();
        confirm().click();
        await vi.advanceTimersByTimeAsync(0);
        expect(showError).toHaveBeenCalledWith("refused");
        expect(dialog().open).toBe(false);
        expect(confirm().disabled).toBe(false);
        expect(refresh).not.toHaveBeenCalled();

        postJson.mockResolvedValue(response(502));
        confirm().click();
        await vi.advanceTimersByTimeAsync(0);
        expect(showError).toHaveBeenLastCalledWith("down");
    });
});

describe("channel-obs-widget source check", () => {
    it("finds a browser source pointing at the link", async () => {
        requests.getInputList.mockResolvedValue({ inputs: [
            { inputName: "Camera", inputKind: "v4l2_input" },
            { inputName: "Other page", inputKind: "browser_source" },
            { inputName: "Relay", inputKind: "browser_source" },
        ] });
        requests.getInputSettings.mockImplementation(async ({ inputName }) =>
            ({ inputSettings: { url: inputName === "Relay" ? URL : "https://example.test" } }));

        page();
        await vi.advanceTimersByTimeAsync(0);

        expect(requests.getInputSettings).not.toHaveBeenCalledWith({ inputName: "Camera" });
        expect(status().textContent).toBe("found");
        expect(status().classList.contains("hidden")).toBe(false);
        expect(missing().classList.contains("hidden")).toBe(true);
    });

    it("offers to create the source when none matches, and creates it on the current scene", async () => {
        requests.getInputList.mockResolvedValue({ inputs: [{ inputName: "Other", inputKind: "browser_source" }] });
        requests.getInputSettings.mockResolvedValue({ inputSettings: { url: "https://example.test" } });
        page();
        await vi.advanceTimersByTimeAsync(0);
        expect(missing().classList.contains("hidden")).toBe(false);

        createBtn().click();
        createBtn().click();
        await vi.advanceTimersByTimeAsync(0);

        expect(requests.createInput).toHaveBeenCalledTimes(1);
        expect(requests.createInput).toHaveBeenCalledWith({
            sceneName: "Main",
            inputName: "Catapult — Relais Twitchat",
            inputKind: "browser_source",
            inputSettings: { url: URL, width: 100, height: 100 },
            sceneItemEnabled: true,
        });
        expect(status().textContent).toBe("found");
        expect(missing().classList.contains("hidden")).toBe(true);
    });

    it("keeps the button and says so when the creation fails", async () => {
        requests.createInput.mockRejectedValue(new Error("nope"));
        page();
        await vi.advanceTimersByTimeAsync(0);

        createBtn().click();
        await vi.advanceTimersByTimeAsync(0);

        expect(showError).toHaveBeenCalledWith("nocreate");
        expect(createBtn().disabled).toBe(false);
        expect(missing().classList.contains("hidden")).toBe(false);
    });

    it("says unreachable when OBS isn't connected", async () => {
        connected = false;
        page();
        expect(status().textContent).toBe("checking");

        await vi.advanceTimersByTimeAsync(10000);

        expect(status().textContent).toBe("unreachable");
        expect(requests.getInputList).not.toHaveBeenCalled();
    });

    it("checks as soon as the page's OBS connection comes up", async () => {
        connected = false;
        page();

        connected = true;
        document.dispatchEvent(new CustomEvent("catapult:obs:connected"));
        await vi.advanceTimersByTimeAsync(0);

        expect(requests.getInputList).toHaveBeenCalled();
        expect(missing().classList.contains("hidden")).toBe(false);
    });

    it("checks once per connect event however many times the card was rendered", async () => {
        page();
        page();
        await vi.advanceTimersByTimeAsync(0);
        requests.getInputList.mockClear();

        document.dispatchEvent(new CustomEvent("catapult:obs:connected"));
        await vi.advanceTimersByTimeAsync(0);

        expect(requests.getInputList).toHaveBeenCalledTimes(1);
    });

    it("drops the previous render's pending wait", async () => {
        connected = false;
        page();
        connected = true;
        page();
        await vi.advanceTimersByTimeAsync(10000);

        expect(status().textContent).not.toBe("unreachable");
    });

    it("says unreachable when OBS refuses the request", async () => {
        requests.getInputList.mockRejectedValue(new Error("closed"));
        page();
        await vi.advanceTimersByTimeAsync(0);

        expect(status().textContent).toBe("unreachable");
    });
});
