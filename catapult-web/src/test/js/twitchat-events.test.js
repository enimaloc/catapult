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

    it("shows the picked icon beside an icon select, none when the option has no picture", () => {
        const select = document.createElement("mdui-select");
        select.className = "icon-select";
        select.innerHTML = '<span slot="icon" class="option-icon"></span>'
            + '<mdui-menu-item value="">none</mdui-menu-item><mdui-menu-item value="add" data-icon="https://x/add.svg">add</mdui-menu-item>';
        document.querySelector(".twitchat-payload-form").appendChild(select);
        const icon = select.querySelector('[slot="icon"]');

        select.value = "add";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        expect(icon.style.getPropertyValue("--option-icon")).toBe("url('https://x/add.svg')");

        select.value = "";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        expect(icon.style.getPropertyValue("--option-icon")).toBe("none");
    });

    it("leaves an icon select without its picture slot alone", () => {
        const select = document.createElement("mdui-select");
        select.className = "icon-select";
        document.querySelector(".twitchat-payload-form").appendChild(select);
        expect(() => select.dispatchEvent(new Event("change", { bubbles: true }))).not.toThrow();
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
        document.body.dataset.labelActionUnreachable = "Catapult is unreachable";
        fetchMock.mockRejectedValueOnce(new Error("offline"));
        document.querySelector(".twitchat-save-btn").click();
        await flush();
        expect(document.querySelector(".twitchat-event-errors").textContent).toBe("Catapult is unreachable");
    });

    it("tells a refusal in the page's language", async () => {
        document.body.dataset.labelActionFailed = "Échec ({0})";
        fetchMock.mockResolvedValueOnce(response(500));
        document.querySelector(".twitchat-save-btn").click();
        await flush();
        expect(document.querySelector("mdui-snackbar").textContent).toBe("Échec (500)");
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

describe("options some Twitchat branches lack", () => {
    /** An icon select as rendered, "quiz" on the beta only, its picked value set like mdui would. */
    function iconSelect(value) {
        html(EVENT(CARD()));
        const select = document.createElement("mdui-select");
        select.className = "icon-select";
        select.dataset.unavailable = "Not on this Twitchat";
        select.innerHTML = '<mdui-menu-item value="add">add</mdui-menu-item>'
            + '<mdui-menu-item value="quiz" data-protocols="beta">quiz</mdui-menu-item>';
        select.value = value;
        document.querySelector(".twitchat-payload-form").appendChild(select);
        return select;
    }
    const quiz = (select) => select.querySelector('[value="quiz"]');

    it("hides them on the other branch, and says so when one is picked", () => {
        const select = iconSelect("quiz");
        CatapultTwitchat.protocol = "stable";
        render();
        expect(quiz(select).hidden).toBe(true);
        expect(select.querySelector('[value="add"]').hidden).toBe(false);
        expect(select.getAttribute("helper")).toBe("Not on this Twitchat");
    });

    it("offers them on their branch", () => {
        const select = iconSelect("quiz");
        CatapultTwitchat.protocol = "beta";
        render();
        expect(quiz(select).hidden).toBe(false);
        expect(select.hasAttribute("helper")).toBe(false);
    });

    it("hides nothing while the branch is unknown", () => {
        const select = iconSelect("quiz");
        CatapultTwitchat.protocol = null;
        render();
        expect(quiz(select).hidden).toBe(false);
        expect(select.hasAttribute("helper")).toBe(false);
    });

    it("drops the warning once another option is picked", () => {
        const select = iconSelect("quiz");
        CatapultTwitchat.protocol = "stable";
        render();
        select.value = "add";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        expect(select.hasAttribute("helper")).toBe(false);
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

    it("flags them against the picked branch while no Twitchat is connected", () => {
        html(EVENT(CARD("QUIZ_REVEAL", '{"beta":"SET_QUIZ_REVEAL"}') + CARD("SEND_MESSAGE")));
        CatapultTwitchat.protocol = "beta"; // forced by configure("beta")
        render();
        const flags = [...document.querySelectorAll(".twitchat-payload-unavailable")].map((flag) => flag.classList.contains("hidden"));
        expect(flags).toEqual([false, true]);
    });

    it("marks nothing on auto while no Twitchat answered", () => {
        html(EVENT(CARD("QUIZ_REVEAL")));
        CatapultTwitchat.protocol = null;
        render();
        expect(document.querySelector(".twitchat-payload-unavailable").classList.contains("hidden")).toBe(true);
    });

    it("re-flags the payloads when the protocol changes", () => {
        html(EVENT(CARD("QUIZ_REVEAL")));
        CatapultTwitchat.protocol = null;
        render();
        CatapultTwitchat.protocol = "beta";
        document.dispatchEvent(new CustomEvent("catapult:twitchat:protocol", { detail: { protocol: "beta" } }));
        expect(document.querySelector(".twitchat-payload-unavailable").classList.contains("hidden")).toBe(false);
        CatapultTwitchat.protocol = null;
        document.dispatchEvent(new CustomEvent("catapult:twitchat:protocol", { detail: { protocol: null } }));
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

describe("button types", () => {
    const SHOW = (values) => `data-show-if='{"path":"actionType","values":${JSON.stringify(values)}}'`;
    const ROW = (hidden = "") => `<div class="repeater-row">
          <select class="twitchat-catapult-action"><option value=""></option><option value="DISABLE_BOT">x</option></select>
          <input data-field="label" value="Go"><input data-field="actionType" value="url">
          <input data-field="url" value="https://x" ${SHOW(["url"])}>
          <mdui-select data-field="triggerId" class="hidden" data-source='{"stable":"TRIGGERS_GET_ALL"}' ${SHOW(["trigger"])}></mdui-select>
          <input data-field="message" value="" class="hidden" ${SHOW(["message", "discord"])}>
          <mdui-button class="repeater-remove"></mdui-button>
        </div>`;
    const BUTTONS = `<div class="twitchat-payload">
      <input type="hidden" class="twitchat-payload-action" value="CUSTOM_CHAT_MESSAGE">
      <input type="hidden" class="twitchat-payload-mode" value="FORM">
      <mdui-switch class="twitchat-payload-enabled"></mdui-switch>
      <div class="twitchat-payload-form">
        <input data-field="message" value="Hi">
        <div data-repeater data-field="actions">
          <div class="repeater-rows">${ROW()}</div>
          <template>${ROW()}</template>
          <mdui-button class="repeater-add"></mdui-button>
        </div>
      </div>
      <p class="twitchat-payload-errors"></p>
    </div>`;
    const row = () => document.querySelector(".repeater-rows > .repeater-row");
    const field = (name) => row().querySelector(`[data-field="${name}"]`);
    const setType = (value) => {
        field("actionType").value = value;
        field("actionType").dispatchEvent(new Event("change", { bubbles: true }));
    };
    const data = () => CatapultTwitchatEvents.payloadOf(document.querySelector(".twitchat-payload"));

    beforeEach(() => { html(EVENT(BUTTONS)); render(); });

    it("shows only the fields of the picked type, and saves only those", () => {
        setType("trigger");
        expect(field("url").classList.contains("hidden")).toBe(true);
        expect(field("triggerId").classList.contains("hidden")).toBe(false);
        field("triggerId").value = "t-1";
        expect(JSON.parse(data().dataJson).actions).toEqual([{ label: "Go", actionType: "trigger", triggerId: "t-1" }]);
        setType("discord");
        expect(field("message").classList.contains("hidden")).toBe(false);
        expect(field("triggerId").classList.contains("hidden")).toBe(true);
    });

    it("drops the Catapult action once the button no longer opens a link", () => {
        const action = row().querySelector(".twitchat-catapult-action");
        action.value = "DISABLE_BOT";
        action.dispatchEvent(new Event("change", { bubbles: true }));
        setType("message");
        expect(action.value).toBe("");
    });

    it("brings the URL back when a Catapult action is picked", () => {
        setType("trigger");
        const action = row().querySelector(".twitchat-catapult-action");
        action.value = "DISABLE_BOT";
        action.dispatchEvent(new Event("change", { bubbles: true }));
        expect(field("actionType").value).toBe("url");
        expect(field("url").classList.contains("hidden")).toBe(false);
    });

    it("fills the trigger list of a row added later", async () => {
        CatapultTwitchat.isTwitchatConnected = () => true;
        CatapultTwitchat.send.mockResolvedValue({ triggerList: [{ id: "t-1", name: "Intro" }] });
        document.querySelector(".repeater-add").click();
        await flush();
        const added = document.querySelectorAll(".repeater-rows > .repeater-row")[1];
        expect(added.querySelector('[data-field="triggerId"] mdui-menu-item')?.getAttribute("value")).toBe("t-1");
    });
});

describe("lists of texts", () => {
    const LIST = `<div class="twitchat-payload">
      <input type="hidden" class="twitchat-payload-action" value="CHAT_POLL_START">
      <input type="hidden" class="twitchat-payload-mode" value="FORM">
      <mdui-switch class="twitchat-payload-enabled"></mdui-switch>
      <div class="twitchat-payload-form">
        <input data-field="title" value="Best?">
        <div data-repeater data-field="choices" class="list-repeater">
          <div class="repeater-rows">
            <div class="repeater-row"><input data-field="value" value="A"></div>
            <div class="repeater-row"><input data-field="value" value=""></div>
            <div class="repeater-row"><input data-field="value" value="B"></div>
          </div>
        </div>
      </div>
    </div>`;

    it("reads a list back as its items' texts, empty ones left out", () => {
        html(EVENT(LIST)); render();
        const data = JSON.parse(CatapultTwitchatEvents.payloadOf(document.querySelector(".twitchat-payload")).dataJson);
        expect(data).toEqual({ title: "Best?", choices: ["A", "B"] });
    });
});
