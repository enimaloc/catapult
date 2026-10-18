/*
 * Broadcasts shown to every visitor, logged in or not: the maintenance banner under the app bar,
 * snackbars for a new version, a cancelled maintenance and alerts, and the restart veil. They come as
 * "Broadcast" events ({name, data}) on the shared live stream. The server replays the remembered
 * maintenance on each new stream, so every handler is idempotent. Texts come from the data-label-*
 * attributes of #broadcast-banner; broadcast text goes into the DOM as text only.
 */
window.CatapultBroadcasts = (function () {
    const TICK_MS = 30_000;
    const DISMISS_PREFIX = "broadcast-dismissed-";
    let maintenance = null; // {startsAt (ISO), start, end (ms), duration, message}
    let timer = null;
    const dismissed = new Set(); // kept in memory too: sessionStorage may be unavailable

    const banner = () => document.getElementById("broadcast-banner");
    const veil = () => document.getElementById("broadcast-veil");
    const lang = () => document.documentElement.lang || undefined;
    // A function replacement: a string one would interpret "$&" and the like in the value.
    const label = (name, value) => (banner()?.dataset[name] ?? "").replace("{0}", () => value ?? "");

    function isDismissed(startsAt) {
        if (dismissed.has(startsAt)) return true;
        try {
            return sessionStorage.getItem(DISMISS_PREFIX + startsAt) === "1";
        } catch {
            return false;
        }
    }

    function remember(startsAt) {
        dismissed.add(startsAt);
        try {
            sessionStorage.setItem(DISMISS_PREFIX + startsAt, "1");
        } catch {
            // dismissal then only lasts until the page is reloaded
        }
    }

    function formatCountdown(ms) {
        const seconds = ms / 1000;
        const format = new Intl.RelativeTimeFormat(lang(), { numeric: "auto" });
        for (const [unit, size] of [["day", 86400], ["hour", 3600], ["minute", 60]]) {
            if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit);
        }
        return format.format(Math.round(seconds), "second");
    }

    function stopTimer() {
        if (timer !== null) clearInterval(timer);
        timer = null;
    }

    function hide() {
        stopTimer();
        const el = banner();
        if (el) el.hidden = true;
    }

    /** Refreshes the countdown, or hides the banner once the maintenance window is over. */
    function tick() {
        const el = banner();
        if (!maintenance || !el || Date.now() >= maintenance.end) {
            hide();
            return;
        }
        el.querySelector("[data-banner-countdown]").textContent = formatCountdown(maintenance.start - Date.now());
    }

    function show() {
        const el = banner();
        if (!el || !maintenance || isDismissed(maintenance.startsAt) || Date.now() >= maintenance.end) {
            hide();
            return;
        }
        el.querySelector("[data-banner-time]").textContent =
            new Intl.DateTimeFormat(lang(), { dateStyle: "medium", timeStyle: "short" }).format(new Date(maintenance.start));
        el.querySelector("[data-banner-duration]").textContent = label("labelDuration", maintenance.duration);
        el.querySelector("[data-banner-message]").textContent = maintenance.message;
        el.hidden = false;
        tick();
        if (timer === null) timer = setInterval(tick, TICK_MS);
    }

    function showSnackbar(text, { action, onAction, ttlSeconds, warning } = {}) {
        const snackbar = document.createElement("mdui-snackbar");
        snackbar.textContent = text;
        // Always closeable by hand: one that never closes by itself (a ttl of 0) would stay for good.
        snackbar.setAttribute("closeable", "");
        if (warning) snackbar.classList.add("broadcast-warning");
        if (Number.isFinite(ttlSeconds)) snackbar.setAttribute("auto-close-delay", String(Math.max(ttlSeconds, 0) * 1000));
        if (action) {
            snackbar.setAttribute("action", action);
            snackbar.addEventListener("action-click", onAction);
        }
        snackbar.open = true;
        snackbar.addEventListener("closed", () => snackbar.remove());
        document.body.appendChild(snackbar);
    }

    function onScheduled(data) {
        const start = Date.parse(data?.startsAt);
        const duration = Number(data?.durationMinutes);
        if (Number.isNaN(start) || !Number.isFinite(duration)) return;
        maintenance = { startsAt: data.startsAt, start, end: start + duration * 60_000, duration, message: data.message ?? "" };
        show();
    }

    function onCancelled(data) {
        const original = Date.parse(data?.originalStartsAt);
        if (!maintenance || Number.isNaN(original) || original !== maintenance.start) return;
        maintenance = null;
        hide();
        showSnackbar(label("labelCancelled", data.reason ?? ""));
    }

    function onVersion(data) {
        if (!data?.version) return;
        const options = data.promptReload
            ? { action: label("labelReload"), onAction: () => location.reload() }
            : {};
        showSnackbar(label("labelVersion", data.version), options);
    }

    function onAlert(data, warning) {
        if (!data?.title && !data?.body) return;
        const text = [data.title, data.body].filter(Boolean).join(": ");
        showSnackbar(text, { ttlSeconds: Number(data.ttlSeconds ?? NaN), warning });
    }

    function onBroadcast(message) {
        const { name, data } = message ?? {};
        switch (name) {
            case "maintenance.scheduled": return onScheduled(data);
            case "maintenance.cancelled": return onCancelled(data);
            case "version.deployed": return onVersion(data);
            case "alert.info": return onAlert(data, false);
            case "alert.warning": return onAlert(data, true);
            case "maintenance.imminent": {
                const el = veil();
                if (el) el.hidden = false;
                return undefined;
            }
            default: return undefined;
        }
    }

    document.addEventListener("click", event => {
        if (!event.target.closest?.("[data-banner-dismiss]") || !maintenance) return;
        remember(maintenance.startsAt);
        hide();
    });
    CatapultLive.on("Broadcast", onBroadcast);
    CatapultLive.onOpen(() => {
        const el = veil();
        if (el) el.hidden = true;
    });

    return { onBroadcast };
})();
