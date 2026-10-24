/*
 * A debugged member's exchanges page (#members/{id}/debug): the filters go to the query string (fragment and
 * address bar, so a reload keeps them), a page or a filter swaps only the results, a row opens the exchange in
 * a dialog (bodies pretty-printed when JSON, every text through textContent) and the recording is deleted once
 * confirmed.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, response } from "./helpers.js";

const ID = "00000000-0000-0000-0000-000000000001";
const E1 = "00000000-0000-0000-0000-0000000000e1";

let fetched;
let posted;
const urls = () => fetched.mock.calls.map(([url]) => url);

const results = rows => `<div data-debug-results>${rows}<button data-debug-page="2"></button></div>`;

function fragment(rows = `<div data-debug-exchange="${E1}"></div>`) {
    return `<div class="admin-debug" data-debug-member="${ID}" data-confirm-text="OK" data-cancel-text="No"
                 data-error="Failed ({0})" data-label-gone="Gone" data-clear-confirm="Delete it all?">
        <button data-admin-back></button>
        <input data-debug-filter="service" value="">
        <input data-debug-filter="direction" value="">
        <input data-debug-filter="outcome" value="">
        <input type="datetime-local" data-debug-filter="from" data-iso="2026-10-24T08:00:00Z">
        <input type="datetime-local" data-debug-filter="to">
        <button data-debug-clear></button>
        ${results(rows)}
        <div id="admin-debug-detail">
            <span data-debug-field="title"></span><span data-debug-field="summary"></span>
            <section data-debug-section="requestHeaders"><pre data-debug-field="requestHeaders"></pre></section>
            <section data-debug-section="requestBody"><pre data-debug-field="requestBody"></pre></section>
            <section data-debug-section="responseHeaders"><pre data-debug-field="responseHeaders"></pre></section>
            <section data-debug-section="responseBody"><pre data-debug-field="responseBody"></pre></section>
            <section data-debug-section="errorMessage"><pre data-debug-field="errorMessage"></pre></section>
            <section data-debug-section="traceId"><code data-debug-field="traceId"></code></section>
        </div>
    </div>`;
}

function page() {
    html(`<mdui-tab-panel value="members">${fragment()}</mdui-tab-panel>`);
    document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "members" } }));
}

const root = () => document.querySelector(".admin-debug");
const field = name => document.querySelector(`[data-debug-field="${name}"]`);
const section = name => document.querySelector(`[data-debug-section="${name}"]`);
const filter = name => document.querySelector(`[data-debug-filter="${name}"]`);

function change(name, value) {
    const input = filter(name);
    input.value = value;
    input.dispatchEvent(new Event("change", { bubbles: true }));
    return flush();
}

const exchange = (extra = {}) => ({
    id: E1, method: "GET", url: "https://api.example/x?y=1", status: 500, durationMs: 1203, service: "steam",
    direction: "OUT", requestHeaders: "{\"A\":\"b\"}", requestBody: null, responseHeaders: "{}", responseBody: "{\"a\":1,\"b\":[2]}",
    errorMessage: null, traceId: null, ...extra
});

beforeEach(async () => {
    history.replaceState(null, "", `/admin#members/${ID}%2Fdebug`);
    fetched = vi.fn(async () => response(200, fragment(`<div data-debug-exchange="${E1}">fresh</div>`)));
    window.fetch = fetched;
    posted = vi.fn(async () => response(204));
    window.CatapultCsrf = { postJson: posted };
    window.CatapultSpa = { navigate: vi.fn(async () => {}) };
    window.mdui = { confirm: vi.fn(() => Promise.resolve()) };
    await load("channel-page", "admin-tabs", "admin-member-debug");
});

describe("admin-member-debug", () => {
    it("shows the period in the fields' local time from the ISO instants the page carries", () => {
        page();

        const local = new Date("2026-10-24T08:00:00Z");
        const pad = n => String(n).padStart(2, "0");
        expect(filter("from").value).toBe(`${local.getFullYear()}-${pad(local.getMonth() + 1)}-${pad(local.getDate())}T${pad(local.getHours())}:${pad(local.getMinutes())}`);
        expect(filter("to").value).toBe("");
    });

    it("puts the filters in the query string, of the request and of the address bar, and swaps only the results", async () => {
        page();
        const before = root();

        await change("service", "steam");
        await change("direction", "OUT");
        await change("outcome", "error");
        await change("to", "2026-10-24T12:30");

        const from = new Date("2026-10-24T08:00:00Z").toISOString();
        const to = new Date("2026-10-24T12:30").toISOString();
        const query = new URLSearchParams({ service: "steam", direction: "OUT", outcome: "error", from, to }).toString();
        expect(urls().at(-1)).toBe(`/spa/admin/members/${ID}/debug?${query}`);
        expect(location.search).toBe(`?${query}`);
        expect(location.hash).toBe(`#members/${ID}%2Fdebug`);
        expect(root()).toBe(before);
        expect(document.querySelector("[data-debug-results]").textContent).toBe("fresh");
    });

    it("leaves a blank filter out, and asks the page it was given with the filters it has", async () => {
        page();
        filter("service").value = "twitch";

        document.querySelector("[data-debug-page]").click();
        await flush();

        const from = new Date("2026-10-24T08:00:00Z").toISOString();
        expect(urls()).toEqual([`/spa/admin/members/${ID}/debug?service=twitch&from=${encodeURIComponent(from)}&page=2`]);
    });

    it("clears a filter when its \"all\" option is picked", async () => {
        page();
        await change("service", "steam");
        await change("outcome", "error");

        await change("service", "all");

        const from = new Date("2026-10-24T08:00:00Z").toISOString();
        const query = new URLSearchParams({ outcome: "error", from }).toString();
        expect(urls().at(-1)).toBe(`/spa/admin/members/${ID}/debug?${query}`);
        expect(location.search).toBe(`?${query}`);
    });

    it("shows \"all\" again on a select whose option was picked twice, which unselects it", async () => {
        page();
        const select = document.createElement("mdui-select");
        select.dataset.debugFilter = "direction";
        filter("direction").replaceWith(select);
        select.value = "";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        await flush();

        expect(select.value).toBe("all");
        expect(urls().at(-1)).not.toContain("direction");
    });

    it("opens an exchange with its bodies indented when they are JSON, as typed otherwise", async () => {
        fetched.mockResolvedValueOnce(response(200, exchange({ requestBody: "<b>not json</b>" })));
        page();

        document.querySelector("[data-debug-exchange]").click();
        await flush();

        expect(urls()).toEqual([`/spa/admin/members/${ID}/debug/${E1}`]);
        expect(field("title").textContent).toBe("GET https://api.example/x?y=1");
        expect(field("summary").textContent).toContain("500");
        expect(field("responseBody").textContent).toBe("{\n  \"a\": 1,\n  \"b\": [\n    2\n  ]\n}");
        expect(field("requestHeaders").textContent).toBe("{\n  \"A\": \"b\"\n}");
        // never parsed as markup
        expect(field("requestBody").textContent).toBe("<b>not json</b>");
        expect(field("requestBody").children).toHaveLength(0);
        expect(document.getElementById("admin-debug-detail").open).toBe(true);
    });

    it("hides what an exchange doesn't have, and shows the transport error and the trace id as text", async () => {
        fetched.mockResolvedValueOnce(response(200, exchange({ responseBody: null, errorMessage: "boom", traceId: "abc123" })));
        page();

        document.querySelector("[data-debug-exchange]").click();
        await flush();

        expect(section("responseBody").hidden).toBe(true);
        expect(section("requestBody").hidden).toBe(true);
        expect(section("errorMessage").hidden).toBe(false);
        expect(field("errorMessage").textContent).toBe("boom");
        // no Sentry organisation is configured to build a link with: the id is shown to be copied
        expect(section("traceId").hidden).toBe(false);
        expect(field("traceId").textContent).toBe("abc123");
        expect(field("traceId").closest("a")).toBeNull();
    });

    it("tells an exchange that can no longer be read", async () => {
        fetched.mockResolvedValueOnce(response(404, ""));
        page();

        document.querySelector("[data-debug-exchange]").click();
        await flush();

        expect(document.getElementById("admin-debug-detail").open).not.toBe(true);
        expect(document.querySelector("mdui-snackbar").textContent).toBe("Gone");
    });

    it("deletes the whole recording once confirmed, then goes back to the list", async () => {
        page();
        const back = vi.fn();
        document.querySelector("[data-admin-back]").addEventListener("click", back);

        document.querySelector("[data-debug-clear]").click();
        await flush();

        expect(mdui.confirm).toHaveBeenCalledWith(expect.objectContaining({ headline: "Delete it all?", confirmText: "OK" }));
        expect(posted).toHaveBeenCalledWith(`/admin/members/${ID}/debug/clear`, {});
        expect(back).toHaveBeenCalledTimes(1);
    });

    it("deletes nothing when the admin declines, nor goes back when the deletion failed", async () => {
        page();
        const back = vi.fn();
        document.querySelector("[data-admin-back]").addEventListener("click", back);

        mdui.confirm.mockReturnValueOnce(Promise.reject());
        document.querySelector("[data-debug-clear]").click();
        await flush();
        expect(posted).not.toHaveBeenCalled();

        posted.mockResolvedValue(response(502));
        document.querySelector("[data-debug-clear]").click();
        await flush();
        expect(posted).toHaveBeenCalledTimes(1);
        expect(back).not.toHaveBeenCalled();
    });

    it("shows the refusal and keeps the list when the results cannot be read", async () => {
        page();
        const before = document.querySelector("[data-debug-results]");

        fetched.mockResolvedValueOnce(response(503, ""));
        await change("service", "steam");
        expect(document.querySelector("mdui-snackbar").textContent).toBe("Failed (503)");

        fetched.mockRejectedValueOnce(new Error("offline"));
        await change("service", "twitch");

        expect(document.querySelector("[data-debug-results]")).toBe(before);
        expect(location.search).toBe("");
    });

    it("keeps the list when the answer carries no results to swap in", async () => {
        page();
        const before = document.querySelector("[data-debug-results]");
        fetched.mockResolvedValueOnce(response(200, "<p>nothing here</p>"));

        await change("service", "steam");

        expect(document.querySelector("[data-debug-results]")).toBe(before);
        expect(location.search).toBe("");
    });

    it("drops the answer to a list that a later one overtook", async () => {
        page();
        let answerFirst;
        fetched.mockImplementationOnce(() => new Promise(resolve => { answerFirst = resolve; }));
        filter("service").value = "steam";
        filter("service").dispatchEvent(new Event("change", { bubbles: true }));
        await change("service", "twitch");

        answerFirst(response(200, fragment("<div data-debug-exchange=\"stale\">stale</div>")));
        await flush();

        expect(document.querySelector("[data-debug-results]").textContent).toBe("fresh");
        expect(location.search).toContain("service=twitch");
    });

    it("tells why an exchange could not be read when it is not simply gone", async () => {
        page();

        fetched.mockResolvedValueOnce(response(500, ""));
        document.querySelector("[data-debug-exchange]").click();
        await flush();
        expect(document.querySelector("mdui-snackbar").textContent).toBe("Failed (500)");

        fetched.mockRejectedValueOnce(new Error("offline"));
        document.querySelector("[data-debug-exchange]").click();
        await flush();

        expect(document.getElementById("admin-debug-detail").open).not.toBe(true);
    });

    it("closes the dialog from its close button", async () => {
        page();
        const dialog = document.createElement("mdui-dialog");
        dialog.open = true;
        dialog.innerHTML = "<button data-debug-close></button>";
        root().append(dialog);

        dialog.querySelector("[data-debug-close]").click();

        expect(dialog.open).toBe(false);
    });

    it("copies the trace id and says so, or says nothing when the clipboard refuses", async () => {
        page();
        const writeText = vi.fn(() => Promise.resolve());
        Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
        window.mdui.snackbar = vi.fn();
        const wrapper = document.createElement("div");
        wrapper.dataset.copied = "Copied";
        wrapper.innerHTML = "<code data-debug-copy>abc123</code>";
        root().append(wrapper);

        wrapper.querySelector("[data-debug-copy]").click();
        await flush();
        expect(writeText).toHaveBeenCalledWith("abc123");
        expect(window.mdui.snackbar).toHaveBeenCalledWith({ message: "Copied" });

        writeText.mockReturnValueOnce(Promise.reject(new Error("denied")));
        wrapper.querySelector("[data-debug-copy]").click();
        await flush();
        expect(window.mdui.snackbar).toHaveBeenCalledTimes(1);
    });

    it("listens once per page, however many times it is attached", async () => {
        page();
        document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "members" } }));
        document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "groups" } }));

        await change("service", "steam");

        expect(fetched).toHaveBeenCalledTimes(1);
    });
});
