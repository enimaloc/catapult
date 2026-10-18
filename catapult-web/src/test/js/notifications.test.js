// catapult-web/src/test/js/notifications.test.js
/* notifications.js: the bell, its badge and its menu, kept live by the page's stream. */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flush, html, load, response } from "./helpers.js";

class FakeEventSource {
    static instances = [];
    constructor(url) {
        this.url = url;
        this.listeners = {};
        FakeEventSource.instances.push(this);
    }
    addEventListener(type, listener) {
        (this.listeners[type] ??= []).push(listener);
    }
    fire(type, data) {
        (this.listeners[type] ?? []).forEach(listener => listener({ data: typeof data === "string" ? data : JSON.stringify(data) }));
    }
}

const TEMPLATE = `
    <template id="notification-item-template">
        <div class="notification-item" data-id="" data-read="false" data-severity="INFO">
            <mdui-icon class="notification-icon" name="info"></mdui-icon>
            <div class="notification-content">
                <div class="notification-title"></div>
                <div class="notification-body"></div>
                <time class="notification-date" datetime=""></time>
                <mdui-button class="notification-cta" hidden></mdui-button>
            </div>
        </div>
    </template>`;

function item(id, { read = false, cta = false, createdAt = "2026-10-18T10:00:00Z" } = {}) {
    return `<div class="notification-item" data-id="${id}" data-read="${read}" data-severity="INFO">
        <div class="notification-title">T${id}</div>
        <time class="notification-date" datetime="${createdAt}"></time>
        ${cta ? `<mdui-button class="notification-cta" href="https://example.com">Go</mdui-button>` : ""}
    </div>`;
}

function menu(unread, items = []) {
    return `<div id="notifications-menu" data-unread="${unread}" data-new="New: {0}">
        <div class="notification-list">${items.join("")}<p class="notification-empty" ${items.length ? "hidden" : ""}>none</p></div>
        <mdui-button data-notifications-read-all>all</mdui-button>${TEMPLATE}</div>`;
}

let fetched;
let menuBody;

function setMenu(body) {
    menuBody = body;
}

async function boot(body) {
    setMenu(body);
    html(`<mdui-dropdown id="notifications-bell" hidden>
        <mdui-button-icon slot="trigger"><mdui-badge id="notifications-badge" hidden></mdui-badge></mdui-button-icon>
        <div id="notifications-dropdown"></div></mdui-dropdown>`);
    await load("csrf", "live", "notifications");
    await flush();
}

const badge = () => document.getElementById("notifications-badge");
const bell = () => document.getElementById("notifications-bell");
const items = () => Array.from(document.querySelectorAll("#notifications-dropdown .notification-item"));
const stream = () => FakeEventSource.instances[0];
const posts = () => fetched.mock.calls.filter(([, init]) => init?.method === "POST").map(([url]) => url);

beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal("EventSource", FakeEventSource);
    delete window.CatapultLive;
    delete window.CatapultNotifications;
    delete window.CatapultCsrf;
    document.documentElement.lang = "en";
    fetched = vi.fn(async (url, init) => (init?.method === "POST" ? response(204) : response(200, menuBody)));
    window.fetch = fetched;
});

describe("notifications.js", () => {
    it("fills the dropdown from the menu fragment and shows the bell with its badge", async () => {
        await boot(menu(3, [item("a"), item("b")]));
        expect(fetched).toHaveBeenCalledWith("/spa/notifications/menu", expect.anything());
        expect(items()).toHaveLength(2);
        expect(bell().hidden).toBe(false);
        expect(badge().hidden).toBe(false);
        expect(badge().textContent).toBe("3");
    });

    it("hides the badge at zero, caps it at 99+, and keeps the bell hidden when the API is down", async () => {
        await boot(menu(0));
        expect(bell().hidden).toBe(false);
        expect(badge().hidden).toBe(true);

        await boot(menu(150));
        expect(badge().textContent).toBe("99+");

        await boot(menu(-1));
        expect(bell().hidden).toBe(true);
    });

    it("marks a clicked unread item read, decrements the badge and leaves the link alone", async () => {
        await boot(menu(2, [item("a", { cta: true }), item("b")]));
        const cta = document.querySelector(".notification-cta");
        const click = new MouseEvent("click", { bubbles: true, cancelable: true });
        cta.dispatchEvent(click);
        await flush();

        expect(click.defaultPrevented).toBe(false);
        expect(posts()).toEqual(["/spa/notifications/a/read"]);
        expect(items()[0].dataset.read).toBe("true");
        expect(badge().textContent).toBe("1");
    });

    it("does not post for an item already read", async () => {
        await boot(menu(1, [item("a", { read: true })]));
        items()[0].click();
        await flush();
        expect(posts()).toEqual([]);
    });

    it("marks everything read", async () => {
        await boot(menu(2, [item("a"), item("b")]));
        document.querySelector("[data-notifications-read-all]").click();
        await flush();

        expect(posts()).toEqual(["/spa/notifications/read-all"]);
        expect(items().every(i => i.dataset.read === "true")).toBe(true);
        expect(badge().hidden).toBe(true);
    });

    it("adds a created notification built from the template, capped at 10, with a snackbar", async () => {
        await boot(menu(10, Array.from({ length: 10 }, (_, i) => item(`o${i}`))));
        stream().fire("NotificationCreated", {
            id: "n1", title: "<b>Hi</b>", bodyHtml: "<p>body</p>", severity: "WARNING",
            ctaUrl: "https://example.com", ctaLabel: "Open", createdAt: "2026-10-18T10:00:00Z", read: false,
        });

        expect(items()).toHaveLength(10);
        const first = items()[0];
        expect(first.dataset.id).toBe("n1");
        expect(first.dataset.severity).toBe("WARNING");
        expect(first.querySelector(".notification-title").textContent).toBe("<b>Hi</b>");
        expect(first.querySelector(".notification-title").children).toHaveLength(0);
        expect(first.querySelector(".notification-body p").textContent).toBe("body");
        const cta = first.querySelector(".notification-cta");
        expect(cta.getAttribute("href")).toBe("https://example.com");
        expect(cta.textContent).toBe("Open");
        expect(cta.hidden).toBe(false);
        expect(document.getElementById("notifications-menu").querySelector('[data-id="o9"]')).toBeNull();
        expect(badge().textContent).toBe("11");
        expect(document.querySelector("mdui-snackbar").textContent).toBe("New: <b>Hi</b>");
    });

    it("updates items and the badge from read, all-read and deleted events", async () => {
        await boot(menu(3, [item("a"), item("b"), item("c")]));

        stream().fire("NotificationRead", { notificationId: "a", unreadCount: 2 });
        expect(items()[0].dataset.read).toBe("true");
        expect(badge().textContent).toBe("2");

        stream().fire("NotificationDeleted", { notificationId: "b", unreadCount: 1 });
        expect(items().map(i => i.dataset.id)).toEqual(["a", "c"]);
        expect(badge().textContent).toBe("1");

        stream().fire("NotificationsAllRead", { unreadCount: 0 });
        expect(items().every(i => i.dataset.read === "true")).toBe(true);
        expect(badge().hidden).toBe(true);
    });

    it("fetches the menu again when the stream reopens", async () => {
        await boot(menu(0));
        stream().fire("open", "");
        stream().fire("open", "");
        await flush();
        expect(fetched.mock.calls.filter(([url]) => url === "/spa/notifications/menu")).toHaveLength(2);
    });

    it("writes dates relative to the page's language", async () => {
        const createdAt = new Date(Date.now() - 5 * 60_000).toISOString();
        await boot(menu(1, [item("a", { createdAt })]));
        const expected = new Intl.RelativeTimeFormat("en", { numeric: "auto" }).format(-5, "minute");
        expect(document.querySelector(".notification-date").textContent).toBe(expected);
    });
});
