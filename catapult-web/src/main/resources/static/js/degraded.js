/*
 * catapult-api's degraded mode, shown to every visitor: the banner under the app bar, which the server
 * renders as it knows it (data-view) and which follows the "Degraded" events of the shared live stream (a
 * DegradedView: {degraded, effects: [{key, value, normalValue}], link, incident}).
 * The server replays the current view on each new stream, degraded or not, so apply is idempotent.
 * Texts come from the data-label* attributes of #degraded-banner and go into the DOM as text only: an
 * effect is worded by its property's label (data-labels), and one without a label isn't shown. A
 * dismissal lasts for this page, until the degraded mode ends: the next one shows the banner again.
 */
window.CatapultDegraded = (function () {
    let dismissed = false;

    /** A property's unit, told by the end of its key: its size in milliseconds and how it is shown. */
    const UNITS = { "-ms": [1, "ms"], "-seconds": [1000, "s"], "-minutes": [60_000, "min"], "-hours": [3_600_000, "h"] };
    /** The units a duration is told in, largest first. */
    const SHOWN = [["h", 3_600_000], ["min", 60_000], ["s", 1000], ["ms", 1]];

    const banner = () => document.getElementById("degraded-banner");
    // A function replacement: a string one would interpret "$&" and the like in the value.
    const fill = (template, value) => (template ?? "").replace("{0}", () => String(value ?? ""));
    const label = (name, value) => fill(banner()?.dataset[name], value);

    function effectLabels(el) {
        try {
            return JSON.parse(el.dataset.labels || "{}");
        } catch {
            return {};
        }
    }

    /**
     * A property's value as a duration in the largest unit, no smaller than its key's, it is at least 2 of exactly
     * ("900000" of a -ms key: "15 min", "60" of a -seconds one: "60 s"); in the key's own unit when none fits (0, 1);
     * as it came when not a whole number or when its key tells no unit.
     */
    function format(key, value) {
        const suffix = Object.keys(UNITS).find(end => key.endsWith(end));
        const amount = value == null || value === "" ? NaN : Number(value);
        if (!suffix || !Number.isInteger(amount)) return String(value ?? "");
        const [own, ownUnit] = UNITS[suffix];
        const ms = amount * own;
        const fit = SHOWN.find(([, size]) => size >= own && ms % size === 0 && ms / size >= 2);
        return fit ? `${ms / fit[1]} ${fit[0]}` : `${amount} ${ownUnit}`;
    }

    /** One line per worded effect, before the "everything else" one; the previous lines go. */
    function effects(el, view) {
        el.querySelectorAll("[data-degraded-effect]").forEach(item => item.remove());
        const rest = el.querySelector('[data-degraded-line="rest"]');
        if (!rest) return;
        const labels = effectLabels(el);
        for (const effect of view.effects ?? []) {
            const template = Object.hasOwn(labels, effect.key) ? labels[effect.key] : null;
            if (typeof template !== "string" || effect.value == null) continue;
            let text = fill(template, format(effect.key, effect.value));
            if (effect.normalValue != null) text += " " + label("labelNormal", format(effect.key, effect.normalValue));
            const item = document.createElement("li");
            item.dataset.degradedEffect = effect.key;
            item.textContent = text + ".";
            rest.before(item);
        }
    }

    function link(el, view) {
        const anchor = el.querySelector("[data-degraded-link]");
        if (!anchor) return;
        if (!view.link) {
            anchor.removeAttribute("href");
            anchor.hidden = true;
            return;
        }
        anchor.href = view.link;
        anchor.textContent = label(view.incident ? "labelIncident" : "labelStatus");
        anchor.hidden = false;
    }

    function apply(view) {
        const el = banner();
        if (!el) return;
        if (!view?.degraded) {
            dismissed = false;
            el.hidden = true;
            return;
        }
        effects(el, view);
        link(el, view);
        el.hidden = dismissed;
    }

    document.addEventListener("click", event => {
        if (!event.target.closest?.("[data-degraded-dismiss]")) return;
        dismissed = true;
        const el = banner();
        if (el) el.hidden = true;
    });
    // The view the server rendered the page with: its lines are worded here
    try {
        const initial = banner()?.dataset.view;
        if (initial) apply(JSON.parse(initial));
    } catch {
        // An unreadable view words nothing; the next "Degraded" event does
    }
    CatapultLive.on("Degraded", apply);

    return { apply };
})();
