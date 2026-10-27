/**
 * Live updates for /channel/{username}: a Server-Sent Events stream that reacts to each
 * ChannelUpdatedEvent subtype published server-side (see event/*.java) by patching just
 * the affected part of the DOM — the same patch a successful mutation in this tab would
 * apply to itself, now driven by the server instead of the client's own postJson() call.
 * That's why the mutation handlers across channel-*.js no longer call CatapultChannel.refresh()
 * after most actions: the SSE echo (including for the tab that triggered the mutation) is
 * the single source of truth for what changed.
 *
 * EventSource reconnects on its own, except when a reconnect is answered with something other
 * than a stream (a maintenance page during a restart, a refusal): it then stops for good,
 * so a closed stream is reopened after a backoff (5 s, doubling up to 60 s, back to 5 s once a
 * stream opens), as live.js does. Events sent while the stream was down are lost: every time it
 * opens again (by itself or reopened), the page catches up on the channel's state as events
 * (GET /spa/channel/{username}/state, see ChannelState.java), applied as if the stream had sent them.
 * It is re-rendered instead only when that state can't be applied in place: it lists other bindings
 * than the page does, or a DTDD mapping card the page doesn't have (or the other way round), or it
 * can't be read. The OBS dock, which shows the binding of the game played now, is re-rendered.
 */
(function () {
    const FIRST_DELAY = 5000;
    const MAX_DELAY = 60000;
    let source = null;
    let sourceUsername = null;
    let registeredEvents = new Set();
    let delay = FIRST_DELAY;
    let retry = null;
    // Whether the channel's stream opened before: an open after that is a reconnection.
    let opened = false;

    // The handful of events that need more than Visibility.dispatch's generic handling —
    // BindingDeletedEvent actually removes a row (not a class toggle or property sync, so
    // no spa:* attribute names it at all) and GameChangedEvent also drives the accordion's
    // own `value` property (see setCurrentGame). Every other SSE-driven template rule is
    // added purely via spa:on/spa:switch/spa:value/spa:in; this map — and this map alone —
    // is what a genuinely new concern (not just a new use of an already-wired event) would
    // ever need a line added to.
    //
    // The settings come whole, for channel-settings.js to set its cards to. A DTDD mapping's change and the
    // custom bindings' list are applied in place by their cards' scripts, which tell when they can't (an
    // event from an older catapult-api, which carries nothing but the channel): the page is re-rendered then.
    // Of the connections, Steam's card follows SteamConnectionStateEvent and Xbox's is flipped here; any
    // other's change re-renders the page.
    const customHandlers = {
        GameChangedEvent: data => CatapultChannel.setCurrentGame(data.bindingId),
        BindingDeletedEvent: data => CatapultChannel.removeBinding(data.bindingId),
        ChannelSettingsUpdatedEvent: data => window.CatapultSettings?.apply(data.settings),
        DtddMappingChangedEvent: data => {
            if (!window.CatapultDtdd?.apply(data)) catchUp();
        },
        // The active custom binding is highlighted in place, their list (create, edit, delete) patched in place.
        CustomBindingChangedEvent: data => window.CatapultCustomBindings?.setActive(data.activeId ?? null),
        CustomBindingsUpdatedEvent: data => {
            if (!window.CatapultCustomBindings?.apply(data.bindings, data.activeId)) catchUp();
        },
        ChannelConnectionChangedEvent: data => {
            if (data.provider === "STEAM") return;
            if (data.provider === "XBOX" && typeof data.connected === "boolean") {
                window.CatapultConnections?.showXboxConnected(data.connected);
                return;
            }
            catchUp();
        },
    };

    function closeSource() {
        if (source) {
            source.close();
            source = null;
        }
        clearTimeout(retry);
        retry = null;
        sourceUsername = null;
        registeredEvents = new Set();
        delay = FIRST_DELAY;
        opened = false;
    }

    function registerEvent(eventName) {
        if (registeredEvents.has(eventName)) {
            return;
        }
        registeredEvents.add(eventName);
        source.addEventListener(eventName, event => handle(eventName, JSON.parse(event.data)));
    }

    /** Applies one of the channel's events to the page, sent by its stream or caught up on. */
    function handle(eventName, data) {
        // The OBS dock applies its own events (channel-dock.js): the handlers below patch the
        // full page, whose elements it doesn't have.
        if (window.CatapultDock?.active()) {
            if (CatapultDock.EVENTS.includes(eventName)) CatapultDock.apply(eventName, data);
            return;
        }
        Visibility.dispatch(eventName, data);
        const handler = customHandlers[eventName];
        if (handler) {
            handler(data);
        }
    }

    // Subscribes to every event name the current DOM's spa:on/spa:switch/spa:value/
    // spa:in attributes reference, plus the few with a customHandlers entry — called on
    // every catapult:render (not just on a fresh connect) so a re-render that reveals a
    // previously-absent element (e.g. an owner-only section) still gets subscribed.
    function registerDiscoveredEvents() {
        Visibility.discoverEventNames(document).forEach(registerEvent);
        Object.keys(customHandlers).forEach(registerEvent);
        if (window.CatapultDock?.active()) CatapultDock.EVENTS.forEach(registerEvent);
    }

    /** Re-renders the page, keeping where the visitor had scrolled to. */
    async function catchUp() {
        const top = window.scrollY;
        await CatapultChannel.refresh();
        window.scrollTo({ top, behavior: "instant" });
    }

    /** Whether the page shows what `state` covers: the same bindings, a DTDD mapping card alike. */
    function fits(state) {
        const listed = new Set(state.filter(entry => entry.name === "BindingUpdatedEvent")
            .map(entry => entry.data?.bindingId));
        const shown = document.getElementById("channel-bindings-list") ? CatapultChannel.getBindingsElements() : [];
        if (shown.length !== listed.size || shown.some(row => !listed.has(row.dataset.bindingId))) return false;
        const dtdd = state.some(entry => entry.name === "DtddMappingChangedEvent");
        return dtdd === !!document.getElementById("channel-dtdd-mapping-card");
    }

    /** Catches up on the channel's state after its stream was down: in place when it fits the page. */
    async function catchUpState(username) {
        if (window.CatapultDock?.active()) return catchUp();
        let state;
        try {
            const response = await fetch(`/spa/channel/${encodeURIComponent(username)}/state${location.search}`,
                { headers: { Accept: "application/json" } });
            if (!response.ok) throw new Error(String(response.status));
            state = await response.json();
        } catch {
            state = null;
        }
        // The visitor moved to another channel meanwhile: its own page is fresh.
        if (sourceUsername !== username) return;
        if (!Array.isArray(state) || !fits(state)) return catchUp();
        state.forEach(entry => handle(entry.name, entry.data));
    }

    function open(username) {
        const current = new EventSource(`/events/channel/${username}`);
        source = current;
        registeredEvents = new Set();
        current.addEventListener("open", () => {
            if (current !== source) return;
            delay = FIRST_DELAY;
            if (opened) catchUpState(username);
            opened = true;
        });
        current.addEventListener("error", () => {
            if (current !== source || current.readyState !== EventSource.CLOSED) return;
            scheduleReopen();
        });
        registerDiscoveredEvents();
    }

    function scheduleReopen() {
        if (retry !== null) return;
        const username = sourceUsername;
        retry = setTimeout(() => {
            retry = null;
            if (sourceUsername === username) open(username);
        }, delay);
        delay = Math.min(delay * 2, MAX_DELAY);
    }

    function connect(username) {
        if (!source || sourceUsername !== username) {
            closeSource();
            sourceUsername = username;
            open(username);
            return;
        }
        registerDiscoveredEvents();
    }

    document.addEventListener("catapult:render", () => {
        const username = CatapultChannel.username();
        if (username) {
            connect(username);
        } else {
            closeSource();
        }
    });
})();
