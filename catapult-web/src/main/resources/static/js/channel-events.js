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
 * than a stream (nginx's maintenance page during a restart, a refusal): it then stops for good,
 * so a closed stream is reopened after a backoff (5 s, doubling up to 60 s, back to 5 s once a
 * stream opens), as live.js does. Events sent while the stream was down are lost: every time it
 * opens again (by itself or reopened), the page is re-rendered to catch up.
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
    // The settings come whole, for channel-settings.js to set its cards to. A DTDD mapping's change
    // carries nothing, and a connection other than Steam's (whose card follows SteamConnectionStateEvent)
    // has no live state on its card: the page is re-rendered for those.
    const customHandlers = {
        GameChangedEvent: data => CatapultChannel.setCurrentGame(data.bindingId),
        BindingDeletedEvent: data => CatapultChannel.removeBinding(data.bindingId),
        ChannelSettingsUpdatedEvent: data => window.CatapultSettings?.apply(data.settings),
        DtddMappingChangedEvent: () => catchUp(),
        ChannelConnectionChangedEvent: data => {
            if (data.provider !== "STEAM") catchUp();
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
        source.addEventListener(eventName, event => {
            const data = JSON.parse(event.data);
            Visibility.dispatch(eventName, data);
            const handler = customHandlers[eventName];
            if (handler) {
                handler(data);
            }
        });
    }

    // Subscribes to every event name the current DOM's spa:on/spa:switch/spa:value/
    // spa:in attributes reference, plus the few with a customHandlers entry — called on
    // every catapult:render (not just on a fresh connect) so a re-render that reveals a
    // previously-absent element (e.g. an owner-only section) still gets subscribed.
    function registerDiscoveredEvents() {
        Visibility.discoverEventNames(document).forEach(registerEvent);
        Object.keys(customHandlers).forEach(registerEvent);
    }

    /** Re-renders the page, keeping where the visitor had scrolled to. */
    async function catchUp() {
        const top = window.scrollY;
        await CatapultChannel.refresh();
        window.scrollTo({ top, behavior: "instant" });
    }

    function open(username) {
        const current = new EventSource(`/events/channel/${username}`);
        source = current;
        registeredEvents = new Set();
        current.addEventListener("open", () => {
            if (current !== source) return;
            delay = FIRST_DELAY;
            if (opened) catchUp();
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
