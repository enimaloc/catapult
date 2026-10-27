/**
 * The channel's OBS dock (pages/channel-dock.html). Its game and bot rows keep the full page's ids,
 * which channel-status.js wires; this wires the CCL and TW switches: the channel-wide ones (sent with
 * the blocked lists they leave untouched) and those of the binding of the game played now.
 *
 * The dock has none of the full page's per-element live rules: every event changing what it shows
 * re-renders it instead (channel-events.js hands those to refresh(), before its own handlers, which
 * expect the full page's bindings list). The events of one change come in bursts (a CCL switch's
 * BindingUpdatedEvent and CclStateEvent), so they are coalesced into one re-render.
 */
window.CatapultDock = (function () {
    const EVENTS = [
        "GameChangedEvent", "BotStateChangedEvent", "CclStateEvent", "BindingUpdatedEvent",
        "BindingDeletedEvent", "TwUpdatedEvent", "TwResetEvent", "TwEnabledStateEvent",
        "ChannelSettingsUpdatedEvent",
    ];
    const COALESCE_MS = 150;
    let pending = null;

    function active() {
        return document.getElementById("obs-dock") !== null;
    }

    function refresh() {
        if (pending !== null) return;
        // spa.js scrolls a render to the top: a dock scrolled down to its TW must stay there.
        pending = setTimeout(async () => {
            pending = null;
            const top = window.scrollY;
            await CatapultChannel.refresh();
            window.scrollTo({ top, behavior: "instant" });
        }, COALESCE_MS);
    }

    /** The ids a switch's data-blocked carries ("" for none). */
    function blocked(sw) {
        return sw.dataset.blocked ? sw.dataset.blocked.split(",") : [];
    }

    function wire() {
        const dock = document.getElementById("obs-dock");
        // A dock reached by client-side navigation (OBS opened on another page) sits in a shell rendered
        // with the app bar: the class hides it, as the server leaves it out of a dock's own shell.
        document.body.classList.toggle("obs-dock-mode", dock !== null);
        if (!dock) return;
        const { on, postSwitch, checkedValues } = CatapultChannel;
        const base = CatapultChannel.baseUrl();

        on("obs-dock-ccl-enabled", "change", event => postSwitch(event.currentTarget, `${base}/settings/ccl`,
            { cclEnabled: event.currentTarget.checked, blockedCcls: blocked(event.currentTarget) }));
        on("obs-dock-tw-enabled", "change", event => postSwitch(event.currentTarget, `${base}/settings/tws`,
            { enabled: event.currentTarget.checked, blockedTws: blocked(event.currentTarget) }));

        const binding = dock.querySelector(".obs-dock-binding");
        const id = binding?.dataset.bindingId;
        if (!id) return;

        on("obs-dock-binding-ccl-enabled", "change", event => postSwitch(event.currentTarget,
            `${base}/bindings/${id}/ccl-toggle`, { enabled: event.currentTarget.checked }));
        // Saving a binding's CCL resends its game too: catapult-api replaces all three at once.
        binding.querySelectorAll(".obs-dock-binding-ccl").forEach(sw => sw.addEventListener("change", () =>
            postSwitch(sw, `${base}/bindings/${id}`, {
                twitchGameId: binding.dataset.gameId || null,
                twitchGameName: binding.dataset.gameName || null,
                ccls: checkedValues(binding, ".obs-dock-binding-ccl"),
            })));

        // A binding's TW routes are binding-scoped, not channel-scoped (see channel-bindings.js).
        on("obs-dock-binding-tw-enabled", "change", event => postSwitch(event.currentTarget,
            `/channel/bindings/${id}/tw-enabled`, { enabled: event.currentTarget.checked }));
        binding.querySelectorAll(".obs-dock-binding-tw").forEach(sw => sw.addEventListener("change", () =>
            postSwitch(sw, `/channel/bindings/${id}/tws`, { tws: checkedValues(binding, ".obs-dock-binding-tw") })));
    }

    document.addEventListener("catapult:render", wire);

    return { EVENTS, active, refresh };
})();
