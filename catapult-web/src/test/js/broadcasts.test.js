// catapult-web/src/test/js/broadcasts.test.js
/* broadcasts.js: the maintenance banner, version / alert snackbars and restart veil shown to every visitor. */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load } from "./helpers.js";

const NOW = new Date("2026-10-18T10:00:00Z");
const iso = offsetMs => new Date(NOW.getTime() + offsetMs).toISOString();
const HOUR = 3600_000;

const PAGE = `
    <div id="broadcast-banner" hidden
         data-label-duration="{0} min" data-label-cancelled="Maintenance cancelled: {0}"
         data-label-version="Version {0} deployed" data-label-reload="Reload">
        <span data-banner-time></span> <span data-banner-countdown></span>
        <span data-banner-duration></span> <span data-banner-message></span>
        <button data-banner-dismiss></button>
    </div>
    <div id="broadcast-veil" hidden></div>`;

let handlers;
let openListeners;
const send = (name, data) => handlers.Broadcast({ name, data });
const snackbars = () => Array.from(document.querySelectorAll("mdui-snackbar"));
const banner = () => document.getElementById("broadcast-banner");

async function boot() {
    handlers = {};
    openListeners = [];
    window.CatapultLive = {
        on: (name, listener) => { handlers[name] = listener; },
        onOpen: listener => openListeners.push(listener),
        onReopen: () => {},
    };
    await load("broadcasts");
}

beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    sessionStorage.clear();
    document.documentElement.lang = "en";
    html(PAGE);
    await boot();
});

describe("maintenance.scheduled", () => {
    it("shows the banner with the message as text, the formatted time and the countdown", () => {
        send("maintenance.scheduled", { startsAt: iso(2 * HOUR), durationMinutes: 30, message: "<b>x</b>" });
        expect(banner().hidden).toBe(false);
        const message = banner().querySelector("[data-banner-message]");
        expect(message.textContent).toBe("<b>x</b>");
        expect(message.querySelector("b")).toBeNull();
        const expected = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso(2 * HOUR)));
        expect(banner().querySelector("[data-banner-time]").textContent).toBe(expected);
        expect(banner().querySelector("[data-banner-countdown]").textContent).toBe("in 2 hours");
        expect(banner().querySelector("[data-banner-duration]").textContent).toBe("30 min");
    });

    it("updates the countdown every 30 seconds", () => {
        send("maintenance.scheduled", { startsAt: iso(2 * HOUR), durationMinutes: 30, message: "" });
        vi.setSystemTime(new Date(NOW.getTime() + HOUR + 60_000));
        vi.advanceTimersByTime(30_000);
        expect(banner().querySelector("[data-banner-countdown]").textContent).toBe("in 59 minutes");
    });

    it("hides the banner once the window has ended", () => {
        send("maintenance.scheduled", { startsAt: iso(HOUR), durationMinutes: 30, message: "" });
        vi.setSystemTime(new Date(NOW.getTime() + HOUR + 30 * 60_000));
        vi.advanceTimersByTime(30_000);
        expect(banner().hidden).toBe(true);
        expect(vi.getTimerCount()).toBe(0);
    });

    it("is idempotent: the same maintenance twice keeps one banner and a dismissal", () => {
        const interval = vi.spyOn(globalThis, "setInterval");
        const data = { startsAt: iso(HOUR), durationMinutes: 30, message: "m" };
        send("maintenance.scheduled", data);
        send("maintenance.scheduled", data);
        expect(interval).toHaveBeenCalledTimes(1);
        banner().querySelector("[data-banner-dismiss]").click();
        send("maintenance.scheduled", data);
        expect(banner().hidden).toBe(true);
        expect(interval).toHaveBeenCalledTimes(1);
    });

    it("dismissal hides, is remembered per startsAt, survives a reload and not another startsAt", async () => {
        const startsAt = iso(HOUR);
        send("maintenance.scheduled", { startsAt, durationMinutes: 30, message: "m" });
        banner().querySelector("[data-banner-dismiss]").click();
        expect(banner().hidden).toBe(true);
        expect(sessionStorage.getItem(`broadcast-dismissed-${startsAt}`)).toBe("1");

        await boot();
        send("maintenance.scheduled", { startsAt, durationMinutes: 30, message: "m" });
        expect(banner().hidden).toBe(true);
        send("maintenance.scheduled", { startsAt: iso(3 * HOUR), durationMinutes: 30, message: "m" });
        expect(banner().hidden).toBe(false);
    });

    it("still works when sessionStorage throws", () => {
        vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("denied"); });
        vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("denied"); });
        send("maintenance.scheduled", { startsAt: iso(HOUR), durationMinutes: 30, message: "m" });
        expect(banner().hidden).toBe(false);
        banner().querySelector("[data-banner-dismiss]").click();
        expect(banner().hidden).toBe(true);
    });
});

describe("maintenance.cancelled", () => {
    it("hides the matching banner and tells why", () => {
        const startsAt = iso(HOUR);
        send("maintenance.scheduled", { startsAt, durationMinutes: 30, message: "m" });
        send("maintenance.cancelled", { originalStartsAt: startsAt, reason: "<i>r</i>" });
        expect(banner().hidden).toBe(true);
        expect(vi.getTimerCount()).toBe(0);
        expect(snackbars()).toHaveLength(1);
        expect(snackbars()[0].textContent).toBe("Maintenance cancelled: <i>r</i>");
        expect(snackbars()[0].querySelector("i")).toBeNull();
    });

    it("shows a reason with replacement patterns literally", () => {
        const startsAt = iso(HOUR);
        send("maintenance.scheduled", { startsAt, durationMinutes: 30, message: "m" });
        send("maintenance.cancelled", { originalStartsAt: startsAt, reason: "a $& b $' c" });
        expect(snackbars()[0].textContent).toBe("Maintenance cancelled: a $& b $' c");
    });

    it("keeps the banner for another maintenance", () => {
        send("maintenance.scheduled", { startsAt: iso(HOUR), durationMinutes: 30, message: "m" });
        send("maintenance.cancelled", { originalStartsAt: iso(5 * HOUR), reason: "r" });
        expect(banner().hidden).toBe(false);
        expect(snackbars()).toHaveLength(0);
    });
});

describe("version.deployed", () => {
    it("offers a reload when asked to", () => {
        const reload = vi.fn();
        vi.stubGlobal("location", { ...window.location, reload });
        send("version.deployed", { version: "1.2", promptReload: true });
        const snackbar = snackbars()[0];
        expect(snackbar.textContent).toBe("Version 1.2 deployed");
        expect(snackbar.getAttribute("action")).toBe("Reload");
        snackbar.dispatchEvent(new Event("action-click"));
        expect(reload).toHaveBeenCalled();
    });

    it("has no action otherwise", () => {
        send("version.deployed", { version: "1.2", promptReload: false });
        expect(snackbars()[0].hasAttribute("action")).toBe(false);
    });
});

describe("alerts", () => {
    it("shows the body literally and auto closes after the ttl", () => {
        send("alert.info", { title: "T", body: "<img src=x onerror=alert(1)>", ttlSeconds: 5 });
        const snackbar = snackbars()[0];
        expect(snackbar.textContent).toContain("<img src=x onerror=alert(1)>");
        expect(snackbar.querySelector("img")).toBeNull();
        expect(snackbar.getAttribute("auto-close-delay")).toBe("5000");
        expect(snackbar.classList.contains("broadcast-warning")).toBe(false);
    });

    it("does not auto close with a ttl of 0, and can then be closed by hand", () => {
        send("alert.info", { title: "T", body: "b", ttlSeconds: 0 });
        expect(snackbars()[0].getAttribute("auto-close-delay")).toBe("0");
        expect(snackbars()[0].hasAttribute("closeable")).toBe(true);
    });

    it("can always be closed by hand", () => {
        send("alert.info", { title: "T", body: "b", ttlSeconds: 5 });
        send("version.deployed", { version: "1.2", promptReload: false });
        expect(snackbars().map(s => s.hasAttribute("closeable"))).toEqual([true, true]);
    });

    it("marks warnings", () => {
        send("alert.warning", { title: "T", body: "b", ttlSeconds: 5 });
        expect(snackbars()[0].classList.contains("broadcast-warning")).toBe(true);
    });

    it("removes a snackbar once closed", () => {
        send("alert.info", { title: "T", body: "b", ttlSeconds: 5 });
        snackbars()[0].dispatchEvent(new Event("closed"));
        expect(snackbars()).toHaveLength(0);
    });
});

describe("maintenance.imminent", () => {
    it("shows the veil until the stream opens again", () => {
        send("maintenance.imminent", { etaSeconds: null });
        expect(document.getElementById("broadcast-veil").hidden).toBe(false);
        openListeners.forEach(listener => listener());
        expect(document.getElementById("broadcast-veil").hidden).toBe(true);
    });
});

describe("bad input", () => {
    it("ignores unknown names and incomplete data", () => {
        expect(() => {
            send("something.else", {});
            send("maintenance.scheduled", {});
            send("maintenance.scheduled", { startsAt: "nope", durationMinutes: 5 });
            send("maintenance.cancelled", undefined);
            send("version.deployed", {});
            send("alert.info", {});
            send("alert.warning", null);
            handlers.Broadcast(undefined);
        }).not.toThrow();
        expect(banner().hidden).toBe(true);
        expect(snackbars()).toHaveLength(0);
    });
});
