/*
 * The top bar's notification bell: its dropdown is the menu fragment (NotificationsController),
 * refetched whenever the live stream reopens, and the stream's events keep it and the badge current
 * in between. Clicks are delegated to the document, as the dropdown's content comes and goes; the
 * /notifications page's #notifications-page list shares them, and the item builder.
 */
window.CatapultNotifications = (function () {
    const MENU_SIZE = 10;
    const PAGE_SIZE = 20;
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
     * An item node for a NotificationDto (as the stream carries it) from the menu's template, or the
     * /notifications page's own when the menu has not loaded. Title and label are text; bodyHtml is
     * markup, sanitized by catapult-api.
     */
    function buildItem(dto) {
        const template = document.getElementById("notification-item-template")
            ?? document.getElementById("notification-page-item-template");
        if (!template) return null;
        const node = template.content.firstElementChild.cloneNode(true);
        node.dataset.id = dto.id;
        node.tabIndex = 0;
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

    /** Shows each list's empty text only while the list has no item. */
    function syncEmpty() {
        document.querySelectorAll(CONTAINERS).forEach(container => {
            const empty = container.querySelector(".notification-empty");
            if (empty) empty.hidden = container.querySelectorAll(".notification-item").length > 0;
        });
    }

    function showSnackbar(text) {
        const snackbar = document.createElement("mdui-snackbar");
        snackbar.textContent = text;
        snackbar.open = true;
        snackbar.addEventListener("closed", () => snackbar.remove());
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

    /** Marks an item read (once) and tells catapult-api. */
    function read(node) {
        if (node.dataset.read === "true") return;
        markRead(node.dataset.id);
        if (unread > 0) setUnread(unread - 1);
        post(`/spa/notifications/${encodeURIComponent(node.dataset.id)}/read`);
    }

    /** Opens the item's CTA like its button does (new tab, noopener) unless the click was on the button itself. */
    function followCta(node, target) {
        const cta = node.querySelector(".notification-cta[href]");
        if (cta && !target.closest(".notification-cta")) {
            window.open(cta.getAttribute("href"), "_blank", "noopener");
        }
    }

    function onClick(event) {
        if (!(event.target instanceof Element)) return;
        // The page's button sits in its header, outside the list: it works wherever it is.
        if (event.target.closest("[data-notifications-read-all]")) {
            document.querySelectorAll(".notification-item").forEach(n => { n.dataset.read = "true"; });
            setUnread(0);
            post("/spa/notifications/read-all");
            return;
        }
        if (!event.target.closest(CONTAINERS)) return;
        const node = event.target.closest(".notification-item");
        if (event.target.closest("[data-link]") && bell()) bell().open = false;
        if (!node) return;
        read(node);
        followCta(node, event.target);
    }

    function onKeydown(event) {
        if ((event.key !== "Enter" && event.key !== " ") || !(event.target instanceof Element)) return;
        const node = event.target.closest(CONTAINERS) && event.target.closest(".notification-item");
        if (!node || event.target !== node) return;
        event.preventDefault();
        read(node);
        followCta(node, node);
    }

    /** Puts a live item on top of a list (the menu keeps its newest MENU_SIZE), unless the list already shows it. */
    function prepend(container, dto, max = Infinity) {
        if (!container || Array.from(container.querySelectorAll(".notification-item")).some(n => n.dataset.id === dto.id)) return false;
        const node = buildItem(dto);
        if (!node) return false;
        container.prepend(node);
        container.querySelectorAll(".notification-item").forEach((n, i) => { if (i >= max) n.remove(); });
        return true;
    }

    function onCreated(dto) {
        // The page lists the newest first: a notification only belongs on top of its first page.
        const page = document.getElementById("notifications-page");
        const onPage = page?.dataset.page === "0" ? prepend(page, dto, PAGE_SIZE) : false;
        const inMenu = prepend(list(), dto, MENU_SIZE);
        if (!onPage && !inMenu && itemsOf(dto.id).length) return;
        syncEmpty();
        if (!dto.read && unread >= 0) setUnread(unread + 1);
        const template = document.getElementById("notifications-menu")?.dataset.new;
        showSnackbar(template ? template.replace("{0}", () => dto.title ?? "") : dto.title);
    }

    if (document.getElementById("notifications-dropdown")) {
        document.addEventListener("click", onClick);
        document.addEventListener("keydown", onKeydown);
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
        document.addEventListener("catapult:render", () => {
            const page = document.getElementById("notifications-page");
            if (page) fillDates(page);
        });
        CatapultLive.onReopen(refresh);
        refresh();
    }

    return { refresh, buildItem, fillDates, formatRelative, setUnread, showSnackbar };
})();
