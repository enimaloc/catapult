/*
 * degraded.js: the banner of catapult-api's degraded mode. It is worded from the data-label* attributes, follows
 * the "Degraded" events of the live stream, and a dismissal lasts until the degraded mode ends.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load } from "./helpers.js";

const LABELS = {
    "poll-interval-ms": "Refresh every {0}",
    "cache-seconds": "Cache kept {0}",
    "retries": "Retries: {0}"
};

let onDegraded;

function banner({ view = null, labels = LABELS, withRest = true, withLink = true } = {}) {
    html(`
        <div id="degraded-banner" hidden data-label-normal="(normally {0})" data-label-incident="Incident"
             data-label-status="Status"${view ? ` data-view='${JSON.stringify(view)}'` : ""}
             data-labels='${JSON.stringify(labels)}'>
            <ul>
                <li data-degraded-effect="stale">stale</li>
                ${withRest ? '<li data-degraded-line="rest">everything else</li>' : ""}
            </ul>
            ${withLink ? "<a data-degraded-link hidden>link</a>" : ""}
            <button data-degraded-dismiss><span id="inner"></span></button>
        </div>`);
}

const el = () => document.getElementById("degraded-banner");
const lines = () => Array.from(el().querySelectorAll("[data-degraded-effect]")).map(li => li.textContent);

async function start(options) {
    window.CatapultLive = { on: vi.fn((name, fn) => { if (name === "Degraded") onDegraded = fn; }) };
    banner(options);
    await load("degraded");
}

const degraded = (effects, extra = {}) => ({ degraded: true, effects, ...extra });

describe("degraded banner", () => {
    beforeEach(() => {
        onDegraded = null;
    });

    it("subscribes to the Degraded events of the live stream", async () => {
        await start();
        expect(window.CatapultLive.on).toHaveBeenCalledWith("Degraded", window.CatapultDegraded.apply);
    });

    it("words the view the server rendered the page with", async () => {
        await start({ view: degraded([{ key: "retries", value: 3 }]) });

        expect(el().hidden).toBe(false);
        expect(lines()).toEqual(["Retries: 3."]);
    });

    it("ignores an unreadable initial view", async () => {
        await start();
        el().dataset.view = "{nope";
        await load("degraded");
        expect(el().hidden).toBe(true);
    });

    it("shows nothing for a missing banner", async () => {
        window.CatapultLive = { on: vi.fn() };
        html("");
        await load("degraded");
        expect(() => window.CatapultDegraded.apply(degraded([]))).not.toThrow();
    });

    it("hides the banner when the view is not degraded", async () => {
        await start();
        onDegraded(degraded([]));
        expect(el().hidden).toBe(false);

        onDegraded({ degraded: false });
        expect(el().hidden).toBe(true);
        onDegraded(null);
        expect(el().hidden).toBe(true);
    });

    it("tells durations in the largest unit that is at least 2 of exactly", async () => {
        await start();
        onDegraded(degraded([
            { key: "poll-interval-ms", value: "900000", normalValue: 60000 },
            { key: "cache-seconds", value: "60" }
        ]));

        expect(lines()).toEqual(["Refresh every 15 min (normally 60 s).", "Cache kept 60 s."]);
    });

    it("keeps the key's own unit when no larger one fits, and non-numbers as they came", async () => {
        await start();
        onDegraded(degraded([
            { key: "poll-interval-ms", value: 1 },
            { key: "cache-seconds", value: 90 },
            { key: "retries", value: "many" }
        ]));

        expect(lines()).toEqual(["Refresh every 1 ms.", "Cache kept 90 s.", "Retries: many."]);
    });

    it("tells a fractional value of a unit key as it came", async () => {
        await start();
        onDegraded(degraded([{ key: "cache-seconds", value: "1.5" }]));
        expect(lines()).toEqual(["Cache kept 1.5."]);
    });

    it("tells hours and minutes keys", async () => {
        await start({ labels: { "ttl-hours": "TTL {0}", "ttl-minutes": "Wait {0}" } });
        onDegraded(degraded([{ key: "ttl-minutes", value: 120 }, { key: "ttl-hours", value: 3 }]));
        expect(lines()).toEqual(["Wait 2 h.", "TTL 3 h."]);
    });

    it("does not show an effect without a label or without a value", async () => {
        await start();
        onDegraded(degraded([
            { key: "unlabelled", value: 1 },
            { key: "toString", value: 1 },
            { key: "retries", value: null },
            { key: "retries", value: 2 }
        ]));
        expect(lines()).toEqual(["Retries: 2."]);
    });

    it("tolerates malformed labels and effects left out", async () => {
        await start();
        el().dataset.labels = "{broken";
        onDegraded({ degraded: true });
        expect(lines()).toEqual([]);
        expect(el().hidden).toBe(false);
    });

    it("puts the effect lines before the rest line and replaces the previous ones", async () => {
        await start();
        onDegraded(degraded([{ key: "retries", value: 1 }]));
        onDegraded(degraded([{ key: "retries", value: 2 }]));

        const items = Array.from(el().querySelectorAll("li")).map(li => li.textContent);
        expect(items).toEqual(["Retries: 2.", "everything else"]);
    });

    it("writes the value as text, not markup or a replacement pattern", async () => {
        await start({ labels: { retries: "<b>{0}</b>" } });
        onDegraded(degraded([{ key: "retries", value: "$& <i>" }]));
        const item = el().querySelector("[data-degraded-effect]");
        expect(item.textContent).toBe("<b>$& <i></b>.");
        expect(item.querySelector("b")).toBeNull();
    });

    it("shows no effect lines when the banner has no rest line", async () => {
        await start({ withRest: false });
        onDegraded(degraded([{ key: "retries", value: 1 }]));
        expect(lines()).toEqual([]);
    });

    it("links to the incident or the status page, or hides the link", async () => {
        await start();
        const anchor = el().querySelector("[data-degraded-link]");

        onDegraded(degraded([], { link: "https://status.example/i/1", incident: true }));
        expect(anchor.hidden).toBe(false);
        expect(anchor.getAttribute("href")).toBe("https://status.example/i/1");
        expect(anchor.textContent).toBe("Incident");

        onDegraded(degraded([], { link: "https://status.example" }));
        expect(anchor.textContent).toBe("Status");

        onDegraded(degraded([]));
        expect(anchor.hidden).toBe(true);
        expect(anchor.hasAttribute("href")).toBe(false);
    });

    it("works for a banner without a link", async () => {
        await start({ withLink: false });
        onDegraded(degraded([], { link: "https://status.example" }));
        expect(el().hidden).toBe(false);
    });

    it("stays dismissed for the degraded mode, and shows again for the next one", async () => {
        await start();
        onDegraded(degraded([]));

        el().querySelector("#inner").click();
        expect(el().hidden).toBe(true);

        onDegraded(degraded([{ key: "retries", value: 1 }]));
        expect(el().hidden).toBe(true);

        onDegraded({ degraded: false });
        onDegraded(degraded([]));
        expect(el().hidden).toBe(false);
    });

    it("ignores clicks elsewhere", async () => {
        await start();
        onDegraded(degraded([]));
        document.body.click();
        expect(el().hidden).toBe(false);
    });

    it("dismisses even when the banner is gone", async () => {
        await start();
        const button = el().querySelector("[data-degraded-dismiss]");
        document.body.append(button);
        el().remove();
        expect(() => button.click()).not.toThrow();
    });
});
