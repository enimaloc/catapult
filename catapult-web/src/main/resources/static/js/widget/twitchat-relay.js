(function () {
    "use strict";
    const body = document.body;
    const widgetToken = body.dataset.widgetToken;

    if (!widgetToken) {
        return;
    }

    // Visible-in-DOM activity log — hidden by default (see twitchat.html's stylesheet),
    // revealable by the streamer via OBS Browser Source's "Custom CSS" field
    // (e.g. "#log{display:block}"). Capped so a long-running OBS session doesn't grow
    // the DOM unbounded.
    const LOG_MAX_LINES = 200;
    const logEl = document.getElementById("log");
    function log(message) {
        console.log("[twitchat-relay]", message);
        if (!logEl) return;
        const line = document.createElement("div");
        line.textContent = "[" + new Date().toLocaleTimeString() + "] " + message;
        logEl.appendChild(line);
        while (logEl.childElementCount > LOG_MAX_LINES) {
            logEl.removeChild(logEl.firstChild);
        }
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
            log("OBS-websocket non connecté, notification abandonnée");
            return;
        }
        claimRelay(obs, notification.id).then((claimed) => {
            if (!claimed) {
                log("Notification déjà relayée par un autre client, ignorée.");
                return;
            }
            log("Relais de la notification : " + notification.message);
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
            }).catch((err) => log("BroadcastCustomEvent a échoué : " + err));
        });
    }

    // Coordinates relaying across every client connected to the same OBS-websocket server
    // (widget page + settings-page fallback, possibly both at once) via OBS's own shared
    // "persistent data" store — the only state genuinely shared between them, since one may
    // run inside OBS's embedded browser (no shared localStorage/BroadcastChannel with a
    // regular browser tab). A random jitter before the check/claim narrows, but doesn't
    // fully close, the race window between two clients relaying the same notification; if
    // the coordination call itself fails, relay anyway rather than silently drop it.
    const RELAY_DEDUP_REALM = "OBS_WEBSOCKET_DATA_REALM_GLOBAL";
    const RELAY_DEDUP_SLOT = "catapult_twitchat_relay_dedup";

    function claimRelay(obsConn, notificationId) {
        const jitterMs = Math.floor(Math.random() * 150);
        return new Promise((resolve) => setTimeout(resolve, jitterMs))
            .then(() => obsConn.call("GetPersistentData", { realm: RELAY_DEDUP_REALM, slotName: RELAY_DEDUP_SLOT }))
            .then((res) => {
                if (res && res.slotValue === notificationId) return false;
                return obsConn.call("SetPersistentData",
                        { realm: RELAY_DEDUP_REALM, slotName: RELAY_DEDUP_SLOT, slotValue: notificationId })
                    .then(() => true);
            })
            .catch(() => true);
    }

    // Twitchat itself listens on the same OBS-websocket connection for a "TRIGGERS_GET_ALL"
    // custom event and answers with "TRIGGER_LIST" ({triggers:[{id,name}]}) — the only
    // discovery mechanism Twitchat's public API exposes for the streamer's configured
    // triggers (see PublicAPI.ts / Chat.vue in Durss/Twitchat). Used here purely to surface
    // what Twitchat currently sees in the activity log — e.g. to help confirm that the
    // trigger a "chat command" quick configuration depends on has actually been created.
    function requestTriggerList() {
        if (!obs) return;
        obs.call("BroadcastCustomEvent", {
            eventData: { origin: "twitchat", type: "TRIGGERS_GET_ALL", data: {} }
        }).catch((err) => log("TRIGGERS_GET_ALL a échoué : " + err));
    }

    function onObsCustomEvent(eventData) {
        if (!eventData || eventData.origin !== "twitchat") return;
        if (eventData.type === "TRIGGER_LIST") {
            const triggers = (eventData.data && eventData.data.triggers) || [];
            if (triggers.length === 0) {
                log("Aucun trigger Twitchat détecté.");
            } else {
                log("Triggers Twitchat détectés (" + triggers.length + ") : "
                    + triggers.map((t) => t.name).join(", "));
            }
        }
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
                log("Connexion OBS-websocket perdue, reconnexion…");
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
            obs.on("CustomEvent", onObsCustomEvent);
            reconnectDelay = RECONNECT_FLOOR_MS;
            log("Connecté à OBS-websocket.");
            requestTriggerList();
        }).catch((err) => {
            if (myGeneration !== generation) return;
            log("Connexion OBS-websocket échouée, nouvel essai… (" + err + ")");
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
