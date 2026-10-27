/*
 * Times kept current in the page: every [data-live-time] element is rewritten each second, so an age, a
 * countdown or a TTL never goes stale between renders. The kind is the attribute's value:
 *  - "relative": the distance to its datetime in words ("3 minutes ago", "in 2 hours");
 *  - "datetime": its datetime as a local date and time;
 *  - "countdown": the time left to its datetime, in data-label-future's {0} ("Expires in {0}"); once
 *    past, the time since in data-label-past's, or else data-label-done as is, the element then
 *    marked data-live-done. A data-live-in of seconds stands for a datetime that many seconds after
 *    the element is first seen, so that a TTL counts from the browser's own clock.
 * Labels come from the page; the script has no text of its own.
 */
window.CatapultTime = (function () {
    const TICK_MS = 1000;
    const RELATIVE_UNITS = [["year", 31536000], ["month", 2592000], ["day", 86400], ["hour", 3600], ["minute", 60]];
    const DURATION_UNITS = [["day", 86400], ["hour", 3600], ["minute", 60], ["second", 1]];

    const lang = () => document.documentElement.lang || undefined;

    /** The distance of a time {@code ms} from now (negative: past) in words, in its largest unit. */
    function relative(ms) {
        const seconds = ms / 1000;
        const format = new Intl.RelativeTimeFormat(lang(), { numeric: "auto" });
        for (const [unit, size] of RELATIVE_UNITS) {
            if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit);
        }
        return format.format(Math.round(seconds), "second");
    }

    /** A length of time, whatever its sign, as its two largest units ("4m 32s", "1h 5m"), seconds rounded up. */
    function duration(ms) {
        let left = Math.ceil(Math.abs(ms) / 1000);
        const parts = [];
        for (const [unit, size] of DURATION_UNITS) {
            const count = Math.floor(left / size);
            left -= count * size;
            if (parts.length === 0 && count === 0 && unit !== "second") continue;
            if (parts.length === 1 && count === 0) break;
            parts.push(new Intl.NumberFormat(lang(), { style: "unit", unit, unitDisplay: "narrow" }).format(count));
            if (parts.length === 2) break;
        }
        return parts.join(" ");
    }

    // A function replacement: a string one would interpret "$&" and the like in the value.
    const fill = (label, value) => (label ?? "").replace("{0}", () => value);

    function update(el, now) {
        if (el.dataset.liveIn !== undefined && !el.hasAttribute("datetime")) {
            const seconds = Number(el.dataset.liveIn);
            if (Number.isFinite(seconds)) el.setAttribute("datetime", new Date(now + seconds * 1000).toISOString());
        }
        const at = Date.parse(el.getAttribute("datetime"));
        if (Number.isNaN(at)) return;
        switch (el.dataset.liveTime) {
            case "relative":
                el.textContent = relative(at - now);
                break;
            case "datetime":
                el.textContent = new Intl.DateTimeFormat(lang(), { dateStyle: "medium", timeStyle: "short" }).format(new Date(at));
                break;
            case "countdown":
                if (at > now) {
                    el.textContent = fill(el.dataset.labelFuture, duration(at - now));
                } else if (el.dataset.labelPast !== undefined) {
                    el.textContent = fill(el.dataset.labelPast, duration(now - at));
                } else {
                    el.textContent = el.dataset.labelDone ?? "";
                    el.setAttribute("data-live-done", "");
                }
                break;
        }
    }

    /** Rewrites every live time under root now. */
    function refresh(root = document) {
        const now = Date.now();
        root.querySelectorAll("[data-live-time]").forEach(el => update(el, now));
    }

    document.addEventListener("catapult:render", () => refresh());
    // An admin tab's content arrives on its own (admin-tabs.js): written at once, not a second later.
    document.addEventListener("catapult:admin-panel", event => refresh(event.detail?.panel ?? document));
    setInterval(refresh, TICK_MS);

    return { relative, duration, refresh };
})();
