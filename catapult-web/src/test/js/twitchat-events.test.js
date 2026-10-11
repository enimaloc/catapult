// catapult-web/src/test/js/twitchat-events.test.js
/*
 * twitchat-events.js on server-rendered markup like TwitchatEventForms': reading payloads back
 * from data-field paths, raw JSON toggling, ordering, saving, testing, live selects, availability.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

const CARD = (action = "CUSTOM_CHAT_MESSAGE", names = '{"stable":"CUSTOM_CHAT_MESSAGE","beta":"CUSTOM_CHAT_MESSAGE"}') => `
<div class="twitchat-payload">
  <input type="hidden" class="twitchat-payload-action" value="${action}">
  <input type="hidden" class="twitchat-payload-mode" value="FORM">
  <input type="hidden" class="twitchat-payload-names" value='${names}'>
  <div class="twitchat-payload-header">
    <mdui-switch class="twitchat-payload-enabled"></mdui-switch>
    <mdui-button class="twitchat-payload-up"></mdui-button><mdui-button class="twitchat-payload-down"></mdui-button>
    <mdui-button class="twitchat-payload-raw-btn"></mdui-button><mdui-button class="twitchat-payload-test"></mdui-button>
    <mdui-button class="twitchat-payload-delete"></mdui-button>
  </div>
  <p class="twitchat-payload-unavailable hidden"></p>
  <div class="twitchat-payload-form">
    <input data-field="message" value="Hi {{gameName}}">
    <input data-field="user.name" value="Bot">
    <input data-field="col" type="number" value="2">
    <textarea data-field="extra" class="json-field">{"a":1}</textarea>
    <div data-repeater data-field="actions">
      <div class="repeater-rows">
        <div class="repeater-row">
          <select class="twitchat-catapult-action"><option value=""></option><option value="DISABLE_BOT">x</option></select>
          <input data-field="label" value="Stop"><input data-field="actionType" value="url"><input data-field="url" value="">
          <mdui-button class="repeater-remove"></mdui-button>
        </div>
      </div>
      <template><div class="repeater-row"><input data-field="label" value=""><mdui-button class="repeater-remove"></mdui-button></div></template>
      <mdui-button class="repeater-add"></mdui-button>
    </div>
  </div>
  <div class="twitchat-payload-raw hidden"><textarea class="twitchat-payload-raw-json"></textarea></div>
  <p class="twitchat-payload-errors"></p>
</div>`;

const EVENT = (cards) => `
<div id="channel-connections"><div class="twitchat-event">
  <input type="hidden" class="twitchat-event-type" value="STREAM_STARTED">
  <div class="twitchat-payloads">${cards}</div>
  <select class="twitchat-add-action"><option value="SEND_MESSAGE">s</option></select>
  <mdui-button class="twitchat-test-all-btn"></mdui-button><mdui-button class="twitchat-save-btn"></mdui-button>
  <p class="twitchat-event-errors"></p>
</div></div>`;

let fetchMock;

beforeEach(async () => {
    history.replaceState(null, "", "/channel/streamer");
    fetchMock = vi.fn(async () => response(204));
    vi.stubGlobal("fetch", fetchMock);
    window.CatapultTwitchat = {
        protocol: "stable", isTwitchatConnected: () => false, send: vi.fn(),
        actionName: (key) => (key === "QUIZ_REVEAL" ? null : key),
    };
    await load("csrf", "channel-page", "twitchat-events");
});

afterEach(() => vi.unstubAllGlobals());

const posted = () => fetchMock.mock.calls.map(([url, init]) => [url, JSON.parse(init.body)]);

describe("reading payloads back", () => {
    it("rebuilds the data from data-field paths, typed, rows included", () => {
        html(EVENT(CARD()));
        const card = document.querySelector(".twitchat-payload");
        card.querySelector(".twitchat-payload-enabled").checked = true;
        expect(CatapultTwitchatEvents.payloadOf(card)).toEqual({
            action: "CUSTOM_CHAT_MESSAGE", mode: "FORM", enabled: true,
            dataJson: JSON.stringify({
                message: "Hi {{gameName}}", user: { name: "Bot" }, col: 2, extra: { a: 1 },
                actions: [{ label: "Stop", actionType: "url" }],
            }),
        });
    });

    it("sends the raw JSON as is in RAW mode", () => {
        html(EVENT(CARD()));
        const card = document.querySelector(".twitchat-payload");
        card.querySelector(".twitchat-payload-mode").value = "RAW";
        card.querySelector(".twitchat-payload-raw-json").value = '{"free":true}';
        expect(CatapultTwitchatEvents.payloadOf(card).dataJson).toBe('{"free":true}');
    });
});

describe("editing", () => {
    beforeEach(() => { html(EVENT(CARD() + CARD("SEND_MESSAGE"))); render(); });

    it("moves a payload down and up", () => {
        const [first] = document.querySelectorAll(".twitchat-payload");
        first.querySelector(".twitchat-payload-down").click();
        expect(document.querySelectorAll(".twitchat-payload")[1]).toBe(first);
        first.querySelector(".twitchat-payload-up").click();
        expect(document.querySelectorAll(".twitchat-payload")[0]).toBe(first);
    });

    it("deletes a payload", () => {
        document.querySelector(".twitchat-payload-delete").click();
        expect(document.querySelectorAll(".twitchat-payload")).toHaveLength(1);
    });

    it("adds and removes repeater rows from the template", () => {
        html(EVENT(CARD())); render(); // the row counts below are document-wide: one card only
        document.querySelector(".repeater-add").click();
        expect(document.querySelectorAll(".repeater-rows > .repeater-row")).toHaveLength(2);
        document.querySelector(".repeater-remove").click();
        expect(document.querySelectorAll(".repeater-rows > .repeater-row")).toHaveLength(1);
    });

    it("points a button at a Catapult action", () => {
        const select = document.querySelector(".twitchat-catapult-action");
        select.value = "DISABLE_BOT";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        const row = select.closest(".repeater-row");
        expect(row.querySelector('[data-field="url"]').value).toBe("{{baseUrl}}/widget/twitchat/action/{{action:DISABLE_BOT}}");
        expect(row.querySelector('[data-field="actionType"]').value).toBe("url");
    });

    it("switches to raw JSON prefilled from the form", () => {
        const card = document.querySelector(".twitchat-payload");
        card.querySelector(".twitchat-payload-raw-btn").click();
        expect(card.querySelector(".twitchat-payload-mode").value).toBe("RAW");
        expect(card.querySelector(".twitchat-payload-form").classList.contains("hidden")).toBe(true);
        expect(JSON.parse(card.querySelector(".twitchat-payload-raw-json").value).user).toEqual({ name: "Bot" });
    });

    it("goes back to the form through the server, which may refuse", async () => {
        const card = document.querySelector(".twitchat-payload");
        card.querySelector(".twitchat-payload-raw-btn").click();
        fetchMock.mockResolvedValueOnce(response(422, [{ index: 0, field: "message", message: "Expected text" }]));
        card.querySelector(".twitchat-payload-raw-btn").click();
        await flush();
        expect(card.querySelector(".twitchat-payload-mode").value).toBe("RAW");
        expect(card.querySelector(".twitchat-payload-errors").textContent).toContain("message");
    });

    it("shows invalid JSON of a json field instead of switching to raw", () => {
        const card = document.querySelector(".twitchat-payload");
        card.querySelector(".json-field").value = "{oops";
        card.querySelector(".twitchat-payload-raw-btn").click();
        expect(card.querySelector(".twitchat-payload-mode").value).toBe("FORM");
        expect(card.querySelector(".twitchat-payload-errors").textContent).not.toBe("");
    });

    it("lets the same action be added twice in a row", async () => {
        const select = document.querySelector(".twitchat-add-action");
        for (let i = 0; i < 2; i++) {
            fetchMock.mockResolvedValueOnce(response(200, CARD("SEND_MESSAGE")));
            select.value = "SEND_MESSAGE";
            select.dispatchEvent(new Event("change", { bubbles: true }));
            await flush();
        }
        expect(document.querySelectorAll(".twitchat-payload")).toHaveLength(4);
    });

    it("re-checks availability on the card that comes back from raw JSON", async () => {
        CatapultTwitchat.isTwitchatConnected = () => true;
        CatapultTwitchat.actionName = () => null;
        const card = document.querySelector(".twitchat-payload");
        card.querySelector(".twitchat-payload-raw-btn").click();
        fetchMock.mockResolvedValueOnce(response(200, CARD("SEND_MESSAGE")));
        card.querySelector(".twitchat-payload-raw-btn").click();
        await flush();
        expect(document.querySelector(".twitchat-payload-unavailable").classList.contains("hidden")).toBe(false);
    });

    it("adds a payload rendered by the server", async () => {
        fetchMock.mockResolvedValueOnce(response(200, CARD("SEND_MESSAGE")));
        document.querySelector(".twitchat-add-action").dispatchEvent(new Event("change", { bubbles: true }));
        await flush();
        expect(document.querySelectorAll(".twitchat-payload")).toHaveLength(3);
        expect(fetchMock.mock.calls.at(-1)[0]).toBe("/channel/streamer/twitchat/payload-form?event=STREAM_STARTED");
    });
});

describe("saving and testing", () => {
    beforeEach(() => { html(EVENT(CARD())); render(); });

    it("saves the event's whole ordered list", async () => {
        document.querySelector(".twitchat-save-btn").click();
        await flush();
        const [url, body] = posted()[0];
        expect(url).toBe("/channel/streamer/twitchat/events/STREAM_STARTED");
        expect(body).toHaveLength(1);
        expect(body[0].action).toBe("CUSTOM_CHAT_MESSAGE");
    });

    it("shows validation errors on the payload they concern", async () => {
        fetchMock.mockResolvedValueOnce(response(400, [{ index: 0, field: "message", message: "Required" }]));
        document.querySelector(".twitchat-save-btn").click();
        await flush();
        expect(document.querySelector(".twitchat-payload-errors").textContent).toBe("message: Required");
    });

    it("refuses to save invalid JSON in a json field, sending nothing", async () => {
        document.querySelector(".json-field").value = "{oops";
        document.querySelector(".twitchat-save-btn").click();
        await flush();
        expect(fetchMock).not.toHaveBeenCalled();
        expect(document.querySelector(".twitchat-event-errors").textContent).not.toBe("");
    });

    it("reports a network failure instead of rejecting", async () => {
        fetchMock.mockRejectedValueOnce(new Error("offline"));
        document.querySelector(".twitchat-save-btn").click();
        await flush();
        expect(document.querySelector(".twitchat-event-errors").textContent).toContain("offline");
    });

    it("reports a network failure when fetching a card", async () => {
        window.CatapultChannel.showError = vi.fn();
        fetchMock.mockRejectedValueOnce(new Error("offline"));
        document.querySelector(".twitchat-add-action").dispatchEvent(new Event("change", { bubbles: true }));
        await flush();
        expect(CatapultChannel.showError).toHaveBeenCalled();
    });

    it("tests one payload, or all of them", async () => {
        document.querySelector(".twitchat-payload-test").click();
        await flush();
        document.querySelector(".twitchat-test-all-btn").click();
        await flush();
        expect(posted().map(([url, body]) => [url, body.length])).toEqual([
            ["/channel/streamer/twitchat/events/STREAM_STARTED/test", 1],
            ["/channel/streamer/twitchat/events/STREAM_STARTED/test", 1],
        ]);
    });
});

describe("testing one payload among several", () => {
    beforeEach(() => { html(EVENT(CARD() + CARD("SEND_MESSAGE") + CARD("EXECUTE_TRIGGER"))); render(); });

    it("shows the errors on the card tested, not on the first one", async () => {
        const cards = document.querySelectorAll(".twitchat-payload");
        cards[0].querySelector(".twitchat-payload-errors").textContent = "message: Required";
        fetchMock.mockResolvedValueOnce(response(400, [{ index: 0, field: "id", message: "Required" }]));
        cards[2].querySelector(".twitchat-payload-test").click();
        await flush();

        expect(posted()[0][1].map(({ action }) => action)).toEqual(["EXECUTE_TRIGGER"]);
        const errors = [...cards].map((card) => card.querySelector(".twitchat-payload-errors").textContent);
        expect(errors).toEqual(["message: Required", "", "id: Required"]);
    });
});

describe("Twitchat-dependent bits", () => {
    it("flags payloads the connected Twitchat can't run", () => {
        html(EVENT(CARD("QUIZ_REVEAL", '{"beta":"SET_QUIZ_REVEAL"}')));
        CatapultTwitchat.isTwitchatConnected = () => true;
        render();
        expect(document.querySelector(".twitchat-payload-unavailable").classList.contains("hidden")).toBe(false);
    });

    it("marks nothing unavailable while the protocol is undetected", () => {
        html(EVENT(CARD("QUIZ_REVEAL")));
        CatapultTwitchat.isTwitchatConnected = () => true;
        CatapultTwitchat.protocol = null;
        render();
        expect(document.querySelector(".twitchat-payload-unavailable").classList.contains("hidden")).toBe(true);
    });

    it("fills a live select from Twitchat, keeping the stored value", async () => {
        html(EVENT(`<div class="twitchat-payload"><mdui-select data-field="id" data-source='{"stable":"TRIGGERS_GET_ALL"}' value="gone">
            <mdui-menu-item value="gone">gone</mdui-menu-item></mdui-select></div>`));
        CatapultTwitchat.isTwitchatConnected = () => true;
        CatapultTwitchat.send.mockResolvedValue({ triggerList: [{ id: "t-1", name: "Intro" }] });
        render();
        await flush();
        const values = [...document.querySelectorAll("mdui-menu-item")].map((item) => [item.value ?? item.getAttribute("value"), item.textContent]);
        expect(values).toEqual([["gone", "gone"], ["t-1", "Intro"]]);
        expect(CatapultTwitchat.send).toHaveBeenCalledWith("TRIGGERS_GET_ALL", {});
    });

    const LIVE = `<div class="twitchat-payload"><mdui-select data-field="id" data-source='{"stable":"TRIGGERS_GET_ALL","beta":"BETA_TRIGGERS"}' value="">
        </mdui-select></div>`;
    const items = () => [...document.querySelectorAll("mdui-menu-item")].map((item) => item.getAttribute("value"));

    it("awaits protocol detection before filling a live select", async () => {
        html(EVENT(LIVE));
        CatapultTwitchat.isTwitchatConnected = () => true;
        CatapultTwitchat.protocol = null;
        CatapultTwitchat.detectProtocol = vi.fn(async () => "beta");
        CatapultTwitchat.send.mockResolvedValue({ triggerList: [{ id: "t-1", name: "Intro" }] });
        render();
        await flush();
        expect(CatapultTwitchat.detectProtocol).toHaveBeenCalled();
        expect(CatapultTwitchat.send).toHaveBeenCalledWith("BETA_TRIGGERS", {});
        expect(items()).toEqual(["t-1"]);
    });

    it("fills live selects when Twitchat shows up later, without duplicating options", async () => {
        html(EVENT(LIVE));
        CatapultTwitchat.send.mockResolvedValue({ triggerList: [{ id: "t-1", name: "Intro" }] });
        render();
        await flush();
        expect(items()).toEqual([]);
        CatapultTwitchat.isTwitchatConnected = () => true;
        document.dispatchEvent(new CustomEvent("catapult:twitchat:presence"));
        await flush();
        document.dispatchEvent(new CustomEvent("catapult:twitchat:presence"));
        await flush();
        expect(items()).toEqual(["t-1"]);
    });
});
