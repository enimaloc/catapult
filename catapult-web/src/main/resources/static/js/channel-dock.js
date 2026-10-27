/**
 * The channel's OBS dock (pages/channel-dock.html). Its game and bot rows keep the full page's ids,
 * which channel-status.js wires; this wires the CCL and TW switches: the channel-wide ones (sent with
 * the blocked lists they leave untouched) and those of the binding of the game played now.
 *
 * Its live events update it in place (channel-events.js hands them to apply(), not to the full page's
 * handlers, which expect its bindings list): the template's spa:* rules through Visibility, as on the
 * full page, and the few patches below for the rest. It is re-rendered only for what it can't patch:
 * the game played now going to another binding (whose switches it hasn't), an event lacking a field
 * its patch reads (sent by an older catapult-web during a deployment), or one arriving while a
 * re-render is under way. Those come in bursts (a game change and its binding's events), so they are
 * coalesced into one re-render.
 */
window.CatapultDock = (function () {
    const EVENTS = [
        "GameChangedEvent", "BotStateChangedEvent", "CclStateEvent", "BindingUpdatedEvent",
        "BindingDeletedEvent", "TwUpdatedEvent", "TwResetEvent", "TwEnabledStateEvent",
        "ChannelSettingsUpdatedEvent",
    ];
    // The payload fields each event's patch reads.
    const FIELDS = {
        GameChangedEvent: ["bindingId", "sourceName"],
        BotStateChangedEvent: ["state"],
        CclStateEvent: ["bindingId", "enabled"],
        BindingUpdatedEvent: ["bindingId", "twitchGameId", "twitchGameName", "ccls"],
        BindingDeletedEvent: ["bindingId"],
        TwUpdatedEvent: ["bindingId", "tws"],
        TwResetEvent: ["bindingId"],
        TwEnabledStateEvent: ["bindingId", "enabled"],
        ChannelSettingsUpdatedEvent: ["settings"],
    };
    const COALESCE_MS = 150;
    let pending = null;
    let rendering = false;

    const byId = id => document.getElementById(id);

    function active() {
        return byId("obs-dock") !== null;
    }

    function refresh() {
        if (pending !== null) return;
        // spa.js scrolls a render to the top: a dock scrolled down to its TW must stay there.
        pending = setTimeout(async () => {
            pending = null;
            rendering = true;
            const top = window.scrollY;
            try {
                await CatapultChannel.refresh();
            } finally {
                rendering = false;
            }
            window.scrollTo({ top, behavior: "instant" });
        }, COALESCE_MS);
    }

    /** The element holding the binding of the game played now, and its id ("" for none). */
    function binding() {
        return document.querySelector("#obs-dock .obs-dock-binding");
    }

    function bindingId() {
        return binding()?.dataset.bindingId ?? "";
    }

    function complete(eventName, data) {
        if (!data || typeof data !== "object") return false;
        if (!FIELDS[eventName].every(field => field in data)) return false;
        return eventName !== "ChannelSettingsUpdatedEvent" || (!!data.settings?.ccl && !!data.settings?.tw);
    }

    /** Sets a channel-wide switch to the feature's state and the blocked list it sends back. */
    function setGlobal(id, enabled, blockedIds) {
        const sw = byId(id);
        if (!sw) return;
        sw.checked = !!enabled;
        sw.dataset.blocked = (blockedIds ?? []).join(",");
    }

    // What the spa:* rules can't do: the binding's game (sent back with its CCL), its removal, and the
    // channel-wide switches, whose state comes nested in the settings.
    const patches = {
        BindingUpdatedEvent(data) {
            const el = binding();
            if (!el || data.bindingId !== bindingId()) return;
            el.dataset.gameId = data.twitchGameId ?? "";
            el.dataset.gameName = data.twitchGameName ?? "";
        },
        BindingDeletedEvent(data) {
            const el = binding();
            if (!el || data.bindingId !== bindingId()) return;
            el.querySelectorAll(".obs-dock-binding-part").forEach(part => part.remove());
            el.querySelectorAll(".obs-dock-no-binding").forEach(p => p.classList.remove("hidden"));
            delete el.dataset.bindingId;
            delete el.dataset.gameId;
            delete el.dataset.gameName;
        },
        ChannelSettingsUpdatedEvent({ settings: { ccl, tw } }) {
            setGlobal("obs-dock-ccl-enabled", ccl.enabled, ccl.blockedCcls);
            setGlobal("obs-dock-tw-enabled", tw.enabled, tw.blockedTws);
            const blockedCcls = new Set(ccl.blockedCcls ?? []);
            binding()?.querySelectorAll(".obs-dock-binding-ccl").forEach(sw => {
                sw.disabled = blockedCcls.has(sw.value);
            });
        },
    };

    /** Brings the dock up to one of its live events: in place when it can, by a re-render otherwise. */
    function apply(eventName, data) {
        if (!EVENTS.includes(eventName) || !active()) return;
        const elsewhere = eventName === "GameChangedEvent" && complete(eventName, data)
            && (data.bindingId ?? "") !== bindingId();
        // A re-render already asked for (or under way) may predate this event: one more catches it.
        if (pending !== null || rendering || elsewhere || !complete(eventName, data)) {
            refresh();
            return;
        }
        Visibility.dispatch(eventName, data);
        patches[eventName]?.(data);
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

    return { EVENTS, active, apply, refresh };
})();
