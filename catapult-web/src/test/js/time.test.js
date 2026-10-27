// catapult-web/src/test/js/time.test.js
/* time.js: relative times, durations and the ticker keeping every [data-live-time] element current. */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, render } from "./helpers.js";

const NOW = new Date("2026-10-18T10:00:00Z");
const iso = offsetMs => new Date(NOW.getTime() + offsetMs).toISOString();
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    document.documentElement.lang = "en";
    await load("time");
});

/** Lets ms go by: the fake clock moves along with the timers. */
const tick = (ms = SECOND) => vi.advanceTimersByTime(ms);

describe("relative", () => {
    it("words the distance in its largest unit, in the page's language", () => {
        expect(CatapultTime.relative(-3 * MINUTE)).toBe("3 minutes ago");
        expect(CatapultTime.relative(2 * HOUR)).toBe("in 2 hours");
        expect(CatapultTime.relative(-DAY)).toBe("yesterday");
        expect(CatapultTime.relative(-5 * SECOND)).toBe("5 seconds ago");
        document.documentElement.lang = "fr";
        expect(CatapultTime.relative(-3 * MINUTE)).toBe("il y a 3 minutes");
    });
});

describe("duration", () => {
    it("gives the two largest units, the second only when not zero", () => {
        expect(CatapultTime.duration(4 * MINUTE + 32 * SECOND)).toBe("4m 32s");
        expect(CatapultTime.duration(HOUR + 5 * MINUTE + 59 * SECOND)).toBe("1h 5m");
        expect(CatapultTime.duration(2 * DAY + 3 * HOUR)).toBe("2d 3h");
        expect(CatapultTime.duration(2 * HOUR)).toBe("2h");
        expect(CatapultTime.duration(12 * SECOND)).toBe("12s");
    });

    it("rounds up to the second, counts down to 0s, and ignores the sign", () => {
        expect(CatapultTime.duration(400)).toBe("1s");
        expect(CatapultTime.duration(0)).toBe("0s");
        expect(CatapultTime.duration(-90 * SECOND)).toBe("1m 30s");
    });

    it("uses the page's language", () => {
        document.documentElement.lang = "fr";
        expect(CatapultTime.duration(2 * DAY + 4 * MINUTE)).toBe("2j");
        expect(CatapultTime.duration(4 * MINUTE + 32 * SECOND)).toBe("4min 32s");
    });
});

describe("live elements", () => {
    it("keeps a relative time current every second", () => {
        html(`<time data-live-time="relative" datetime="${iso(-59 * SECOND)}"></time>`);
        render();
        const time = document.querySelector("time");
        expect(time.textContent).toBe("59 seconds ago");
        tick();
        expect(time.textContent).toBe("1 minute ago");
    });

    it("writes an absolute date and time in the page's language", () => {
        html(`<time data-live-time="datetime" datetime="${iso(HOUR)}"></time>`);
        render();
        const expected = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso(HOUR)));
        expect(document.querySelector("time").textContent).toBe(expected);
    });

    it("counts down to a datetime, then counts up with the past label", () => {
        html(`<span data-live-time="countdown" datetime="${iso(2 * SECOND)}"
                    data-label-future="Expires in {0}" data-label-past="Expired {0} ago"></span>`);
        render();
        const span = document.querySelector("span");
        expect(span.textContent).toBe("Expires in 2s");
        tick();
        expect(span.textContent).toBe("Expires in 1s");
        tick(2 * SECOND);
        expect(span.textContent).toBe("Expired 1s ago");
    });

    it("counts down seconds given at render, from the moment it first sees them", () => {
        tick(5 * SECOND);
        html(`<span data-live-time="countdown" data-live-in="90" data-label-future="in {0}"></span>`);
        render();
        const span = document.querySelector("span");
        expect(span.textContent).toBe("in 1m 30s");
        tick();
        expect(span.textContent).toBe("in 1m 29s");
    });

    it("shows the done label and marks the element once a countdown without a past label ends", () => {
        html(`<span data-live-time="countdown" data-live-in="1" data-label-future="Blocked — {0}" data-label-done="Available"></span>`);
        render();
        const span = document.querySelector("span");
        expect(span.hasAttribute("data-live-done")).toBe(false);
        tick();
        expect(span.textContent).toBe("Available");
        expect(span.hasAttribute("data-live-done")).toBe(true);
    });

    it("puts replacement patterns of a label in literally", () => {
        html(`<span data-live-time="countdown" data-live-in="5" data-label-future="$& {0} $'"></span>`);
        render();
        expect(document.querySelector("span").textContent).toBe("$& 5s $'");
    });

    it("picks up elements added later, and leaves an unparseable datetime alone", () => {
        html(`<time data-live-time="relative" datetime="not a date">kept</time>`);
        render();
        expect(document.querySelector("time").textContent).toBe("kept");
        document.body.insertAdjacentHTML("beforeend", `<time id="late" data-live-time="relative" datetime="${iso(-2 * MINUTE)}"></time>`);
        tick();
        expect(document.getElementById("late").textContent).toBe("2 minutes ago");
    });

    it("writes an admin tab's times as soon as the tab is loaded", () => {
        html(`<div id="panel"><time data-live-time="relative" datetime="${iso(-HOUR)}"></time></div>`);
        const panel = document.getElementById("panel");
        document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: "broadcast", panel } }));
        expect(panel.querySelector("time").textContent).toBe("1 hour ago");
    });

    it("refreshes a subtree on demand", () => {
        html(`<div id="root"><time data-live-time="relative" datetime="${iso(-HOUR)}"></time></div>`);
        CatapultTime.refresh(document.getElementById("root"));
        expect(document.querySelector("time").textContent).toBe("1 hour ago");
    });
});
