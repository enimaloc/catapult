(function () {
    "use strict";
    const body = document.body;
    const widgetToken = body.dataset.widgetToken;

    if (!widgetToken) {
        return;
    }

    // Mutable: a widget page left open across a settings save must pick up the new
    // host/port/password live (see applyNewObsSettings) rather than keep dialing the
    // values it was rendered with — see settings-updated handling below.
    let obsHost = body.dataset.obsHost || null;
    let obsPort = body.dataset.obsPort || null;
    let obsPassword = body.dataset.obsPassword || "";

    // Reconnect strategy mirrors ws-client.js: exponential backoff capped at 30s, reset to
    // the floor on a successful (re)connect. Without this, an OBS restart or any transient
    // drop of the local obs-websocket connection kills the relay until the page is reloaded
    // by hand — there is nothing else that would bring it back on its own.
    const RECONNECT_FLOOR_MS = 1000;
    const RECONNECT_CEILING_MS = 30000;
    let reconnectDelay = RECONNECT_FLOOR_MS;
    let obs = null;

    // Bumped every time applyNewObsSettings swaps in new connection info, so an
    // in-flight connect attempt or a pending reconnect timer started under the OLD
    // host/port/password can recognise it has been superseded and quietly no-op
    // instead of racing the new connection.
    let generation = 0;

    function relay(notification) {
        if (!obs) {
            // Dropped, not queued: a stale relay isn't worth buffering for, and the
            // next live notification will go through once reconnected.
            console.warn("[twitchat-relay] OBS-websocket not connected, dropping notification");
            return;
        }
        obs.call("BroadcastCustomEvent", {
            eventData: {
                origin: "twitchat",
                type: "CUSTOM_CHAT_MESSAGE",
                data: {
                    message: notification.message,
                    style: notification.style,
                    icon: notification.icon,
                    user: notification.authorName ? { name: notification.authorName } : undefined,
                    actions: (notification.actions || []).map((a) => ({
                        label: a.label,
                        actionType: a.actionType,
                        url: a.url,
                        message: a.message,
                        theme: a.theme
                    }))
                }
            }
        }).catch((err) => console.error("[twitchat-relay] BroadcastCustomEvent failed", err));
    }

    function scheduleReconnect(myGeneration) {
        setTimeout(() => {
            if (myGeneration !== generation) {
                return; // settings changed under us — a fresh connectToObs() already owns this
            }
            connectToObs();
        }, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_CEILING_MS);
    }

    function connectToObs() {
        if (!obsHost || !obsPort) {
            return; // not configured yet — applyNewObsSettings() will call back in once it is
        }
        const myGeneration = generation;
        obsWsConnect({
            host: obsHost,
            port: Number(obsPort),
            password: obsPassword,
            onDisconnect: () => {
                if (myGeneration !== generation) return;
                obs = null;
                console.warn("[twitchat-relay] OBS-websocket connection lost, reconnecting…");
                scheduleReconnect(myGeneration);
            }
        }).then((connection) => {
            if (myGeneration !== generation) {
                // Settings changed while this attempt was in flight — this connection is
                // for stale credentials, close it rather than let it linger and relay on.
                connection.close();
                return;
            }
            obs = connection;
            reconnectDelay = RECONNECT_FLOOR_MS;
        }).catch((err) => {
            if (myGeneration !== generation) return;
            console.error("[twitchat-relay] OBS-websocket connection failed, retrying…", err);
            scheduleReconnect(myGeneration);
        });
    }

    // Applies a live settings update pushed by the server (see the ws:event handling
    // below). Handles all three cases uniformly: first-time configuration (host/port were
    // never set at page load), a real change while already connected, and a no-op save
    // (unrelated fields changed, host/port/password identical) — the last one is filtered
    // out so an unrelated settings save doesn't bounce a healthy connection.
    function applyNewObsSettings(newHost, newPort, newPassword) {
        const normalizedPort = newPort == null ? null : String(newPort);
        if (newHost === obsHost && normalizedPort === obsPort && (newPassword || "") === obsPassword) {
            return;
        }
        generation++;
        if (obs) {
            obs.close();
            obs = null;
        }
        obsHost = newHost || null;
        obsPort = normalizedPort;
        obsPassword = newPassword || "";
        reconnectDelay = RECONNECT_FLOOR_MS;
        connectToObs();
    }

    connectToObs();

    let subscribed = false;

    function subscribe() {
        if (subscribed) {
            return;
        }
        subscribed = true;
        // The subscription itself is still required: it is what makes the server
        // resolve twitchat.widget.<token> to catapult:events:twitchat:<ownerId> and
        // register this session for fan-out. No callback is passed, because
        // ws-client dispatches per-channel callbacks under the *public* channel name
        // carried by the frame — and the server does not rewrite the twitchat
        // internal channel, so incoming frames arrive with channel
        // "catapult:events:twitchat:<ownerId>" and would never match this key.
        window.catapultWs.subscribe("twitchat.widget." + widgetToken);
    }

    if (window.catapultWs) {
        // ws-client re-dispatches every typed frame on document as "ws:<type>"
        // with detail = the full frame, so filter event frames by their name.
        document.addEventListener("ws:event", (e) => {
            const frame = e.detail;
            if (!frame || !frame.data) return;
            if (frame.name === "twitchat.notify") {
                relay(frame.data);
            } else if (frame.name === "twitchat.widget.settings.updated") {
                applyNewObsSettings(frame.data.obsHost, frame.data.obsPort, frame.data.obsPassword);
            }
        });
        // ws-client.js already has its own reconnect-with-backoff for the catapult side,
        // including re-sending "subscribe" on every reconnect (resubscribeAll) — no extra
        // handling needed here beyond the initial subscribe below.
        document.addEventListener("ws:auth.ok", subscribe, { once: true });
        // twitchat.widget.<token> requires no session; subscribe immediately too in
        // case ws:auth.ok never fires here (e.g. an OBS browser source with no cookies).
        subscribe();
    }
})();
