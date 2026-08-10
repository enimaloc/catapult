(function () {
    "use strict";
    const body = document.body;
    const widgetToken = body.dataset.widgetToken;
    const obsHost = body.dataset.obsHost;
    const obsPort = body.dataset.obsPort;
    const obsPassword = body.dataset.obsPassword;

    if (!widgetToken || !obsHost || !obsPort) {
        return;
    }

    // Reconnect strategy mirrors ws-client.js: exponential backoff capped at 30s, reset to
    // the floor on a successful (re)connect. Without this, an OBS restart or any transient
    // drop of the local obs-websocket connection kills the relay until the page is reloaded
    // by hand — there is nothing else that would bring it back on its own.
    const RECONNECT_FLOOR_MS = 1000;
    const RECONNECT_CEILING_MS = 30000;
    let reconnectDelay = RECONNECT_FLOOR_MS;
    let obs = null;

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
                        theme: a.theme
                    }))
                }
            }
        }).catch((err) => console.error("[twitchat-relay] BroadcastCustomEvent failed", err));
    }

    function scheduleReconnect() {
        setTimeout(connectToObs, reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_CEILING_MS);
    }

    function connectToObs() {
        obsWsConnect({
            host: obsHost,
            port: Number(obsPort),
            password: obsPassword,
            onDisconnect: () => {
                obs = null;
                console.warn("[twitchat-relay] OBS-websocket connection lost, reconnecting…");
                scheduleReconnect();
            }
        }).then((connection) => {
            obs = connection;
            reconnectDelay = RECONNECT_FLOOR_MS;
        }).catch((err) => {
            console.error("[twitchat-relay] OBS-websocket connection failed, retrying…", err);
            scheduleReconnect();
        });
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
            if (frame && frame.name === "twitchat.notify" && frame.data) {
                relay(frame.data);
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
