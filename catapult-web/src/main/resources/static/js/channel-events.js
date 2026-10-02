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

    function currentChannelUsername() {
        const segments = location.pathname.split("/");
        return segments[1] === "channel" && segments[2] ? segments[2] : null;
    }

    function closeSource() {
        if (source) {
            source.close();
            source = null;
        }
        sourceUsername = null;
    }

    function connect(username) {
        if (source && sourceUsername === username) {
            return;
        }
        closeSource();
        sourceUsername = username;

        source = new EventSource(`/events/channel/${username}`);

        // Every event also runs through Visibility.dispatch, which reads whatever
        // data-on="name:ThisEventName.field" breadcrumbs the current DOM happens to carry
        // (see visibility.js) — a new spa:on in the template is enough to wire a fresh
        // SSE-driven visibility rule; no new line is ever needed here for that part.
        function on(eventName, handler) {
            source.addEventListener(eventName, event => {
                const data = JSON.parse(event.data);
                Visibility.dispatch(eventName, data);
                if (handler) handler(data);
            });
        }

        on("ChannelLiveStateEvent");
        on("BotStateChangedEvent");
        on("GameChangedEvent", data => CatapultChannel.setCurrentGame(data.bindingId));

        on("BindingIgnoredStateEvent");
        on("CclStateEvent");
        on("BindingDeletedEvent", data => CatapultChannel.removeBinding(data.bindingId));
        on("BindingUpdatedEvent");

        on("TwEnabledStateEvent");
        on("TwUpdatedEvent", data => CatapultChannel.setBindingTws(data.bindingId, data.tws));
        on("TwResetEvent", data => CatapultChannel.resetBindingTws(data.bindingId));

        on("MinecraftEnrollEvent", data => CatapultChannel.setMinecraftStatus(data.status, data.minecraftName));
        on("MinecraftSyncEvent", data => CatapultChannel.setMinecraftStatus(data.status, data.minecraftName));
        on("MinecraftDisconnectedEvent", () => CatapultChannel.setMinecraftStatus("NONE", null));

        on("SteamConnectionStateEvent");
        on("SteamTokenSavedEvent");
        on("SteamTokenSharedStateEvent");
        on("SteamTokenDeletedEvent");
    }

    document.addEventListener("catapult:render", () => {
        const username = currentChannelUsername();
        if (username) {
            connect(username);
        } else {
            closeSource();
        }
    });
})();
