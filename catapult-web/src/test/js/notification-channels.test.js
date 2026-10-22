// catapult-web/src/test/js/notification-channels.test.js
/* notification-channels.js: the /notifications page's "also receive by" switches. */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, render, response } from "./helpers.js";

const CARD = `<div id="notification-channels">
    <mdui-switch id="tc" data-channel="TWITCHAT"></mdui-switch>
    <mdui-switch id="chat" data-channel="STREAM_START_CHAT"></mdui-switch></div>`;

let fetched;
let status;

async function boot() {
    html(CARD);
    await load("csrf", "channel-page", "notification-channels");
    render();
}

function toggle(id, checked) {
    const sw = document.getElementById(id);
    sw.checked = checked;
    sw.dispatchEvent(new Event("change"));
    return sw;
}

const posted = () => fetched.mock.calls.map(([url, init]) => [url, JSON.parse(init.body)]);

beforeEach(() => {
    delete window.CatapultChannel;
    delete window.CatapultCsrf;
    status = 204;
    fetched = vi.fn(async () => response(status));
    window.fetch = fetched;
});

describe("notification-channels.js", () => {
    it("sends the whole chosen set when a switch changes", async () => {
        await boot();

        toggle("tc", true);
        await flush();
        toggle("chat", true);
        await flush();

        expect(posted()).toEqual([
            ["/spa/notifications/channels", { channels: ["TWITCHAT"] }],
            ["/spa/notifications/channels", { channels: ["TWITCHAT", "STREAM_START_CHAT"] }],
        ]);
    });

    it("sends an empty set once everything is switched off", async () => {
        await boot();
        document.getElementById("tc").checked = true;

        toggle("tc", false);
        await flush();

        expect(posted()).toEqual([["/spa/notifications/channels", { channels: [] }]]);
    });

    it("puts the switch back when catapult-api refuses", async () => {
        status = 502;
        await boot();

        const sw = toggle("chat", true);
        await flush();

        expect(sw.checked).toBe(false);
    });

    it("puts the switch back when the request cannot be sent", async () => {
        window.fetch = vi.fn(async () => { throw new Error("offline"); });
        await boot();

        const sw = toggle("tc", true);
        await flush();

        expect(sw.checked).toBe(false);
    });

    it("sends one request at a time, the later one carrying the final set", async () => {
        const releases = [];
        window.fetch = fetched = vi.fn(() => new Promise(resolve => releases.push(() => resolve(response(204)))));
        await boot();

        toggle("tc", true);
        toggle("chat", true);
        await flush();
        expect(fetched).toHaveBeenCalledTimes(1);

        releases[0]();
        await flush();
        expect(fetched).toHaveBeenCalledTimes(2);
        expect(posted().at(-1)).toEqual(["/spa/notifications/channels", { channels: ["TWITCHAT", "STREAM_START_CHAT"] }]);
    });

    it("binds each switch once, however many times the page renders", async () => {
        await boot();
        render();
        render();

        toggle("tc", true);
        await flush();

        expect(fetched).toHaveBeenCalledTimes(1);
    });

    it("does nothing on a page without the card", async () => {
        html("<p>other</p>");
        await load("csrf", "channel-page", "notification-channels");
        render();
        expect(fetched).not.toHaveBeenCalled();
    });
});
