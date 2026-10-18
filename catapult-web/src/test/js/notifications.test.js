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

function menu(unread, items = [], template = TEMPLATE) {
    return `<div id="notifications-menu" data-unread="${unread}" data-new="New: {0}">
        <div class="notification-list">${items.join("")}<p class="notification-empty" ${items.length ? "hidden" : ""}>none</p></div>
        <mdui-button data-notifications-read-all>all</mdui-button>${template}</div>`;
}

let fetched;
let menuBody;

function setMenu(body) {
    menuBody = body;
}

async function boot(body, page = "") {
    setMenu(body);
    html(`${page}<mdui-dropdown id="notifications-bell" hidden>
        <mdui-button-icon slot="trigger"><mdui-badge id="notifications-badge" hidden></mdui-badge></mdui-button-icon>
        <div id="notifications-dropdown"></div></mdui-dropdown>`);
    await load("csrf", "live", "notifications");
    await flush();
}

const badge = () => document.getElementById("notifications-badge");
const bell = () => document.getElementById("notifications-bell");
const items = () => Array.from(document.querySelectorAll("#notifications-dropdown .notification-item"));
const pageItems = () => Array.from(document.querySelectorAll("#notifications-page .notification-item"));
// As pages/notifications.html lays it out: the read-all button is in the header, outside the list.
const pageMarkup = (number, ids = []) => `<div class="section-header"><mdui-button id="page-read-all" data-notifications-read-all>all</mdui-button></div>
    <div id="notifications-page" data-page="${number}">${ids.map(id => item(id)).join("")}
    <p class="notification-empty" ${ids.length ? "hidden" : ""}>none</p></div>`;
const created = id => ({ id, title: "t" + id, bodyHtml: "", severity: "INFO", createdAt: "2026-10-18T10:00:00Z", read: false });
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

    it("shows a title with replacement patterns literally in the snackbar", async () => {
        await boot(menu(0));
        stream().fire("NotificationCreated", { ...created("n1"), title: "a $& b $` c" });
        expect(document.querySelector("mdui-snackbar").textContent).toBe("New: a $& b $` c");
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

    it("opens the CTA when the item itself is clicked, once, and not twice from the button", async () => {
        const open = vi.fn();
        vi.stubGlobal("open", open);
        await boot(menu(2, [item("a", { cta: true }), item("b")]));

        document.querySelector(".notification-title").click();
        await flush();
        expect(open).toHaveBeenCalledTimes(1);
        expect(open).toHaveBeenCalledWith("https://example.com", "_blank", "noopener");
        expect(posts()).toEqual(["/spa/notifications/a/read"]);

        open.mockClear();
        document.querySelector(".notification-cta").click();
        expect(open).not.toHaveBeenCalled();

        items()[1].click();
        expect(open).not.toHaveBeenCalled();
    });

    it("reads an item with Enter or Space, following its CTA", async () => {
        const open = vi.fn();
        vi.stubGlobal("open", open);
        await boot(menu(2, [item("a", { cta: true }), item("b")]));

        items()[0].dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true }));
        items()[1].dispatchEvent(new KeyboardEvent("keydown", { key: " ", bubbles: true, cancelable: true }));
        await flush();

        expect(posts()).toEqual(["/spa/notifications/a/read", "/spa/notifications/b/read"]);
        expect(open).toHaveBeenCalledTimes(1);
        expect(badge().hidden).toBe(true);
    });

    it("gives items built from events a tab stop", async () => {
        await boot(menu(0));
        stream().fire("NotificationCreated", { id: "n1", title: "t", bodyHtml: "", severity: "INFO", createdAt: "2026-10-18T10:00:00Z", read: false });
        expect(items()[0].tabIndex).toBe(0);
    });

    it("ignores a created event for an item already shown", async () => {
        await boot(menu(1, [item("n1")]));
        stream().fire("NotificationCreated", { id: "n1", title: "t", bodyHtml: "", severity: "INFO", createdAt: "2026-10-18T10:00:00Z", read: false });
        expect(items()).toHaveLength(1);
        expect(badge().textContent).toBe("1");
        expect(document.querySelector("mdui-snackbar")).toBeNull();
    });

    it("removes a snackbar once it closed", async () => {
        await boot(menu(0));
        stream().fire("NotificationCreated", { id: "n1", title: "t", bodyHtml: "", severity: "INFO", createdAt: "2026-10-18T10:00:00Z", read: false });
        document.querySelector("mdui-snackbar").dispatchEvent(new Event("closed"));
        expect(document.querySelector("mdui-snackbar")).toBeNull();
    });

    it("posts once per click (no handler survives from an earlier load) and refetches the menu when a read fails", async () => {
        await boot(menu(1, [item("a")]));
        fetched.mockImplementation(async (url, init) => (init?.method === "POST" ? response(500) : response(200, menuBody)));
        items()[0].click();
        await flush();

        expect(posts()).toEqual(["/spa/notifications/a/read"]);
        expect(fetched.mock.calls.filter(([url]) => url === "/spa/notifications/menu")).toHaveLength(2);
    });

    describe("on the /notifications page", () => {
        it("fills the relative dates of the rendered items", async () => {
            await boot(menu(0), pageMarkup(0, ["a"]));
            document.dispatchEvent(new CustomEvent("catapult:render"));
            expect(pageItems()[0].querySelector(".notification-date").textContent).not.toBe("");
        });

        it("marks an item read with the same POST", async () => {
            await boot(menu(1, [item("b")]), pageMarkup(0, ["a"]));
            pageItems()[0].click();
            await flush();
            expect(pageItems()[0].dataset.read).toBe("true");
            expect(posts()).toEqual(["/spa/notifications/a/read"]);
        });

        it("marks everything read from its own button", async () => {
            await boot(menu(2, [item("b")]), pageMarkup(0, ["a", "c"]));
            document.getElementById("page-read-all").click();
            await flush();
            expect(pageItems().every(n => n.dataset.read === "true")).toBe(true);
            expect(posts()).toEqual(["/spa/notifications/read-all"]);
            expect(badge().hidden).toBe(true);
        });

        it("prepends a created notification on page 0 only", async () => {
            await boot(menu(0), pageMarkup(0, ["a"]));
            stream().fire("NotificationCreated", created("n1"));
            expect(pageItems().map(n => n.dataset.id)).toEqual(["n1", "a"]);
            expect(document.querySelector("#notifications-page .notification-empty").hidden).toBe(true);
            expect(pageItems()[0].querySelector(".notification-date").textContent).not.toBe("");
        });

        it("builds live items from its own template when the menu has none", async () => {
            const own = TEMPLATE.replace('id="notification-item-template"', 'id="notification-page-item-template"');
            await boot(menu(0, [], ""), pageMarkup(0, ["a"]) + own);
            stream().fire("NotificationCreated", created("n1"));
            expect(pageItems().map(n => n.dataset.id)).toEqual(["n1", "a"]);
        });

        it("keeps the page at twenty items when a notification is created", async () => {
            await boot(menu(0), pageMarkup(0, Array.from({ length: 20 }, (_, i) => "p" + i)));
            stream().fire("NotificationCreated", created("n1"));
            expect(pageItems()).toHaveLength(20);
            expect(pageItems()[0].dataset.id).toBe("n1");
            expect(pageItems().at(-1).dataset.id).toBe("p18");
        });

        it("leaves page 1 alone when a notification is created", async () => {
            await boot(menu(0), pageMarkup(1, ["a"]));
            stream().fire("NotificationCreated", created("n1"));
            expect(pageItems().map(n => n.dataset.id)).toEqual(["a"]);
            expect(document.querySelector("mdui-snackbar")).not.toBeNull();
        });

        it("shows the empty state back once its last item is deleted", async () => {
            await boot(menu(0), pageMarkup(0, ["a"]));
            stream().fire("NotificationDeleted", { notificationId: "a", unreadCount: 0 });
            expect(pageItems()).toHaveLength(0);
            expect(document.querySelector("#notifications-page .notification-empty").hidden).toBe(false);
        });

        it("removes a deleted notification from the page too", async () => {
            await boot(menu(0, [item("a")]), pageMarkup(0, ["a", "b"]));
            stream().fire("NotificationDeleted", { notificationId: "a", unreadCount: 0 });
            expect(pageItems().map(n => n.dataset.id)).toEqual(["b"]);
            expect(items()).toHaveLength(0);
        });

        it("marks an item read on the page when the stream says so", async () => {
            await boot(menu(1), pageMarkup(0, ["a"]));
            stream().fire("NotificationRead", { notificationId: "a", unreadCount: 0 });
            expect(pageItems()[0].dataset.read).toBe("true");
        });
    });
});
