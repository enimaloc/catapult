/*
 * The top bar's notification bell: its dropdown is the menu fragment (NotificationsController),
 * refetched whenever the live stream reopens, and the stream's events keep it and the badge current
 * in between. Clicks are delegated to the document, as the dropdown's content comes and goes; the
 * /notifications page's #notifications-page list shares them, and the item builder.
 */
window.CatapultNotifications = (function () {
    const MENU_SIZE = 10;
    const CONTAINERS = "#notifications-dropdown, #notifications-page";
    const ICONS = { INFO: "info", WARNING: "warning", ERROR: "error" };
    let unread = -1;

    const bell = () => document.getElementById("notifications-bell");
    const badge = () => document.getElementById("notifications-badge");
    const list = () => document.querySelector("#notifications-dropdown .notification-list");

    /** The badge for a count: hidden at zero, "99+" beyond 99; -1 (catapult-api unreachable) hides the bell too. */
    function setUnread(count) {
        unread = Number.isFinite(count) ? count : -1;
        const el = badge();
        if (bell()) bell().hidden = unread < 0;
        if (!el) return;
        el.hidden = unread <= 0;
        el.textContent = unread > 99 ? "99+" : String(Math.max(unread, 0));
    }

    function formatRelative(iso) {
        const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
        if (Number.isNaN(seconds)) return "";
        const units = [["year", 31536000], ["month", 2592000], ["day", 86400], ["hour", 3600], ["minute", 60]];
        const format = new Intl.RelativeTimeFormat(document.documentElement.lang || undefined, { numeric: "auto" });
        for (const [unit, size] of units) {
            if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit);
        }
        return format.format(Math.round(seconds), "second");
    }

    /** Writes the relative text of every `<time datetime>` under root. */
    function fillDates(root = document) {
        root.querySelectorAll(".notification-date[datetime]").forEach(time => {
            time.textContent = formatRelative(time.getAttribute("datetime"));
        });
    }

    /**
     * An item node for a NotificationDto (as the stream carries it) from the menu's template. Title and
     * label are text; bodyHtml is markup, sanitized by catapult-api.
     */
    function buildItem(dto) {
        const template = document.getElementById("notification-item-template");
        if (!template) return null;
        const node = template.content.firstElementChild.cloneNode(true);
        node.dataset.id = dto.id;
        node.dataset.read = String(Boolean(dto.read));
        node.dataset.severity = dto.severity;
        const icon = node.querySelector(".notification-icon");
        icon.setAttribute("name", ICONS[dto.severity] ?? "info");
        const label = icon.getAttribute(`data-label-${String(dto.severity).toLowerCase()}`);
        if (label) icon.setAttribute("title", label);
        node.querySelector(".notification-title").textContent = dto.title;
        node.querySelector(".notification-body").innerHTML = dto.bodyHtml ?? "";
        const time = node.querySelector(".notification-date");
        time.setAttribute("datetime", dto.createdAt);
        time.textContent = formatRelative(dto.createdAt);
        const cta = node.querySelector(".notification-cta");
        if (dto.ctaUrl) {
            cta.setAttribute("href", dto.ctaUrl);
            cta.textContent = dto.ctaLabel || dto.ctaUrl;
            cta.hidden = false;
        } else {
            cta.remove();
        }
        return node;
    }

    const itemsOf = id => Array.from(document.querySelectorAll(".notification-item[data-id]")).filter(n => n.dataset.id === id);

    function syncEmpty() {
        const empty = document.querySelector("#notifications-dropdown .notification-empty");
        if (empty) empty.hidden = document.querySelectorAll("#notifications-dropdown .notification-item").length > 0;
    }

    function showSnackbar(text) {
        const snackbar = document.createElement("mdui-snackbar");
        snackbar.textContent = text;
        snackbar.open = true;
        document.body.appendChild(snackbar);
    }

    async function refresh() {
        let response;
        try {
            response = await fetch("/spa/notifications/menu", { headers: { Accept: "text/html" } });
        } catch {
            return;
        }
        const target = document.getElementById("notifications-dropdown");
        if (!response.ok || !target) return;
        target.innerHTML = await response.text();
        fillDates(target);
        setUnread(Number(target.querySelector("#notifications-menu")?.dataset.unread ?? -1));
    }

    async function post(url) {
        try {
            const response = await CatapultCsrf.postJson(url);
            if (!response.ok) refresh();
        } catch {
            refresh();
        }
    }

    function markRead(id) {
        itemsOf(id).forEach(n => { n.dataset.read = "true"; });
    }

    function onClick(event) {
        if (!(event.target instanceof Element) || !event.target.closest(CONTAINERS)) return;
        if (event.target.closest("[data-notifications-read-all]")) {
            document.querySelectorAll(".notification-item").forEach(n => { n.dataset.read = "true"; });
            setUnread(0);
            post("/spa/notifications/read-all");
            return;
        }
        const node = event.target.closest(".notification-item");
        if (event.target.closest("[data-link]") && bell()) bell().open = false;
        if (!node || node.dataset.read === "true") return;
        markRead(node.dataset.id);
        if (unread > 0) setUnread(unread - 1);
        post(`/spa/notifications/${encodeURIComponent(node.dataset.id)}/read`);
    }

    function onCreated(dto) {
        const container = list();
        if (container) {
            const node = buildItem(dto);
            if (node) {
                container.prepend(node);
                container.querySelectorAll(".notification-item").forEach((n, i) => { if (i >= MENU_SIZE) n.remove(); });
                syncEmpty();
            }
        }
        if (!dto.read && unread >= 0) setUnread(unread + 1);
        const template = document.getElementById("notifications-menu")?.dataset.new;
        showSnackbar(template ? template.replace("{0}", dto.title) : dto.title);
    }

    if (document.getElementById("notifications-dropdown")) {
        document.addEventListener("click", onClick);
        CatapultLive.on("NotificationCreated", onCreated);
        CatapultLive.on("NotificationRead", data => {
            markRead(data.notificationId);
            setUnread(data.unreadCount);
        });
        CatapultLive.on("NotificationDeleted", data => {
            itemsOf(data.notificationId).forEach(n => n.remove());
            syncEmpty();
            setUnread(data.unreadCount);
        });
        CatapultLive.on("NotificationsAllRead", () => {
            document.querySelectorAll(".notification-item").forEach(n => { n.dataset.read = "true"; });
            setUnread(0);
        });
        CatapultLive.onReopen(refresh);
        refresh();
    }

    return { refresh, buildItem, fillDates, formatRelative, setUnread, showSnackbar };
})();
