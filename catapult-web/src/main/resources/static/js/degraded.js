/*
 * catapult-api's degraded mode, shown to every visitor: the banner under the app bar, which the server
 * renders as it knows it and which follows the "Degraded" events of the shared live stream (a
 * DegradedView: {degraded, gameDetectionIntervalSeconds, minecraftFriendsSyncMinutes, link, incident}).
 * The server replays the current view on each new stream, degraded or not, so apply is idempotent.
 * Texts come from the data-label-* attributes of #degraded-banner and go into the DOM as text only. A
 * dismissal lasts for this page, until the degraded mode ends: the next one shows the banner again.
 */
window.CatapultDegraded = (function () {
    let dismissed = false;

    const banner = () => document.getElementById("degraded-banner");
    // A function replacement: a string one would interpret "$&" and the like in the value.
    const label = (name, value) => (banner()?.dataset[name] ?? "").replace("{0}", () => String(value ?? ""));

    /** One effect's line: worded with its value, hidden when catapult-api tells none. */
    function line(el, name, labelName, value) {
        const item = el.querySelector(`[data-degraded-line="${name}"]`);
        if (!item) return;
        item.hidden = value == null;
        item.textContent = value == null ? "" : label(labelName, value);
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
        line(el, "game", "labelGame", view.gameDetectionIntervalSeconds);
        line(el, "minecraft", "labelMinecraft", view.minecraftFriendsSyncMinutes);
        link(el, view);
        el.hidden = dismissed;
    }

    document.addEventListener("click", event => {
        if (!event.target.closest?.("[data-degraded-dismiss]")) return;
        dismissed = true;
        const el = banner();
        if (el) el.hidden = true;
    });
    CatapultLive.on("Degraded", apply);

    return { apply };
})();
