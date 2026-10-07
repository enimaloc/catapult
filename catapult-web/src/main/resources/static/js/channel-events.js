/**
 * Live updates for /channel/{username}: a Server-Sent Events stream that reacts to each
 * ChannelUpdatedEvent subtype published server-side (see ws/event/*.java) by patching just
 * the affected part of the DOM — the same patch a successful mutation in this tab would
 * apply to itself, now driven by the server instead of the client's own postJson() call.
 * That's why the mutation handlers across channel-*.js no longer call CatapultChannel.refresh()
 * after most actions: the SSE echo (including for the tab that triggered the mutation) is
 * the single source of truth for what changed.
 *
 * EventSource reconnects on its own (no manual retry logic needed here).
 */
(function () {
    let source = null;
    let sourceUsername = null;
    let registeredEvents = new Set();

    // The handful of events that need more than Visibility.dispatch's generic handling —
    // BindingDeletedEvent actually removes a row (not a class toggle or property sync, so
    // no spa:* attribute names it at all) and GameChangedEvent also drives the accordion's
    // own `value` property (see setCurrentGame). Every other SSE-driven template rule is
    // added purely via spa:on/spa:switch/spa:value/spa:in; this map — and this map alone —
    // is what a genuinely new concern (not just a new use of an already-wired event) would
    // ever need a line added to.
    const customHandlers = {
        GameChangedEvent: data => CatapultChannel.setCurrentGame(data.bindingId),
        BindingDeletedEvent: data => CatapultChannel.removeBinding(data.bindingId),
    };

    function closeSource() {
        if (source) {
            source.close();
            source = null;
        }
        sourceUsername = null;
        registeredEvents = new Set();
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

    function connect(username) {
        if (!source || sourceUsername !== username) {
            closeSource();
            sourceUsername = username;
            source = new EventSource(`/events/channel/${username}`);
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
