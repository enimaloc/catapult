/**
 * Client of Twitchat's public API, through the page's OBS connection (obs.js): Twitchat talks
 * over obs-websocket custom events, { origin: "twitchat", id, type, data }, which OBS echoes to
 * every connected client. Names and answers come from twitchat-protocol.js (generated from
 * Twitchat's source); IDE typings from src/types/twitchat.d.ts.
 *
 * - actions.<name>(data) / send(ACTION, data): sends an action; a "get" one resolves with the
 *   data of Twitchat's answer (TRIGGERS_GET_ALL -> TRIGGER_LIST's), the others once sent.
 * - on / off / once: Twitchat's events, and actions other clients send ("*" for all of them).
 * - relay(notification): Catapult's notifications, posted in Twitchat's chat. Each arrives as a
 *   "catapult:twitchat:notify" event on document, its detail being the TwitchatNotification.
 */
window.CatapultTwitchat = (function () {
    const { actions: ACTIONS, events: EVENTS, replies: REPLIES } = CatapultTwitchatProtocol;
    const KNOWN_ACTIONS = new Set(ACTIONS);
    const DEFAULT_TIMEOUT_MS = 3000;
    // Ids of the messages this page sent, to drop OBS's echo of them; capped like Twitchat's.
    const MAX_SENT_IDS = 1000;

    /**
     * Every failure of CatapultTwitchat's own: code "UNKNOWN_ACTION" (with the closest known
     * action as `suggestion`), "NOT_CONNECTED" (OBS isn't) or "TIMEOUT" (no answer in time).
     * Failures of OBS itself stay CatapultObs.ObsError.
     */
    class TwitchatError extends Error {
        constructor(message, { code, action = null, suggestion = null }) {
            super(message);
            this.name = "TwitchatError";
            this.code = code;
            this.action = action;
            this.suggestion = suggestion;
        }
    }

    const listeners = new Map();
    const sentIds = new Set();

    function log(message, ...args) {
        console.log(`[twitchat] ${message}`, ...args);
    }

    const shortcutName = (action) => action.toLowerCase().replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());
    const actionName = (shortcut) => shortcut.replace(/[A-Z]/g, (c) => `_${c}`).toUpperCase();

    function emit(type, envelope) {
        listeners.get(type)?.forEach((handler) => handler(envelope.data ?? {}, envelope));
    }

    CatapultObs.on("CustomEvent", (eventData) => {
        if (eventData?.origin !== "twitchat" || !eventData.type) return;
        if (eventData.id && sentIds.has(eventData.id)) return;
        emit(eventData.type, eventData);
        emit("*", eventData);
    });

    /** A random v4 UUID: crypto.randomUUID() only exists on secure contexts (HTTPS, localhost). */
    function randomId() {
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }

    function broadcast(action, data) {
        const id = randomId();
        sentIds.add(id);
        if (sentIds.size > MAX_SENT_IDS) sentIds.delete(sentIds.values().next().value);
        // Always a data object, as Twitchat's own clients send: some of its handlers read it unchecked.
        return CatapultObs.requests.broadcastCustomEvent({ eventData: { origin: "twitchat", id, type: action, data: data ?? {} } });
    }

    /** send(), with action names in the errors written by `style` (shortcut or not). */
    function sendAs(action, data, { timeout = DEFAULT_TIMEOUT_MS } = {}, style = (name) => name) {
        if (!KNOWN_ACTIONS.has(action)) {
            const suggestion = CatapultSuggest.closest(action, ACTIONS);
            return Promise.reject(new TwitchatError(`${style(action)} isn't a Twitchat action`
                + (suggestion ? `, did you mean ${style(suggestion)}?` : ""), { code: "UNKNOWN_ACTION", action, suggestion }));
        }
        if (!CatapultObs.isConnected()) {
            return Promise.reject(new TwitchatError(`Can't send ${action}: OBS isn't connected`, { code: "NOT_CONNECTED", action }));
        }
        const answers = REPLIES[action];
        if (!answers) return broadcast(action, data).then(() => undefined);
        return new Promise((resolve, reject) => {
            // Listening before sending: the answer can't be missed.
            const unsubscribes = answers.map((type) => client.on(type, (answer) => {
                done();
                resolve(answer);
            }));
            const timer = setTimeout(() => {
                done();
                reject(new TwitchatError(`No answer from Twitchat to ${action} within ${timeout}ms`, { code: "TIMEOUT", action }));
            }, timeout);
            function done() {
                clearTimeout(timer);
                unsubscribes.forEach((unsubscribe) => unsubscribe());
            }
            broadcast(action, data).catch((error) => {
                done();
                reject(error);
            });
        });
    }

    // Read on any object by await, JSON.stringify, test matchers…: never action names.
    const NOT_ACTIONS = new Set(["then", "catch", "finally", "toJSON", "constructor", "asymmetricMatch", "nodeType", "$$typeof"]);
    const shortcuts = new Map();

    function shortcut(name) {
        if (!shortcuts.has(name)) {
            const action = actionName(name);
            shortcuts.set(name, (data, options) => sendAs(action, data, options, shortcutName));
        }
        return shortcuts.get(name);
    }

    // The Proxy's target: one real property per known action, for the console's autocompletion.
    const listedShortcuts = Object.fromEntries(ACTIONS.map((action) => [shortcutName(action), shortcut(shortcutName(action))]));

    // Relaying Catapult's notifications. Several clients may be connected to the same OBS (this
    // page in two tabs, the old widget page in OBS's browser source…): they claim each
    // notification in OBS's persistent data, the only storage they all share, so only one posts it.
    const DEDUP_REALM = "OBS_WEBSOCKET_DATA_REALM_GLOBAL";
    const DEDUP_SLOT = "catapult_twitchat_relay_dedup";
    const MAX_JITTER_MS = 150;

    /**
     * Whether this client should relay the notification: false when another one already
     * claimed it. The random jitter narrows (but can't close) the race between two clients
     * checking at once. If OBS fails to answer, relays anyway: a duplicate beats a lost one.
     */
    async function claim(notificationId) {
        await new Promise((resolve) => setTimeout(resolve, Math.floor(Math.random() * MAX_JITTER_MS)));
        try {
            const { slotValue } = await CatapultObs.requests.getPersistentData({ realm: DEDUP_REALM, slotName: DEDUP_SLOT });
            if (slotValue === notificationId) return false;
            await CatapultObs.requests.setPersistentData({ realm: DEDUP_REALM, slotName: DEDUP_SLOT, slotValue: notificationId });
        } catch (error) {
            log("relay coordination failed, relaying anyway", error);
        }
        return true;
    }

    const client = {
        ACTIONS,
        EVENTS,
        REPLIES,
        TwitchatError,

        /**
         * One shortcut per action: actions.chatFeedPause() is send("CHAT_FEED_PAUSE"), and
         * actions.triggersGetAll() resolves with TRIGGER_LIST's data. An unknown one rejects with
         * the closest known shortcut suggested.
         */
        actions: new Proxy(listedShortcuts, {
            get(_, name) {
                if (typeof name !== "string" || NOT_ACTIONS.has(name)) return undefined;
                return shortcut(name);
            },
        }),

        /** Whether Twitchat can be reached, i.e. the page is connected to OBS. */
        isConnected() {
            return CatapultObs.isConnected();
        },

        /**
         * Sends an action. A "get" one (see REPLIES) resolves with the data of the first answer,
         * or rejects with a TIMEOUT TwitchatError without one in time (`options.timeout`, 3s by
         * default): an overlay's presence is only answered while that overlay is open. The others
         * resolve once sent.
         */
        send(action, data, options) {
            return sendAs(action, data, options);
        },

        /**
         * Subscribes to a Twitchat event, an action another client sent, or "*" for all of them.
         * The handler gets the data and the whole message ({ origin, id, type, data }); messages
         * this page sent itself are left out. Returns the unsubscribe function.
         */
        on(type, handler) {
            if (!listeners.has(type)) listeners.set(type, new Set());
            listeners.get(type).add(handler);
            return () => client.off(type, handler);
        },

        off(type, handler) {
            listeners.get(type)?.delete(handler);
        },

        /** Resolves with the data of the next such event. */
        once(type) {
            return new Promise((resolve) => {
                const unsubscribe = client.on(type, (data) => {
                    unsubscribe();
                    resolve(data);
                });
            });
        },

        /**
         * Posts a Catapult notification in Twitchat's chat. Resolves with whether this page posted
         * it: not while OBS is disconnected (dropped rather than queued: a late notification is
         * stale), nor when another client claimed it already.
         */
        async relay(notification) {
            if (!CatapultObs.isConnected()) {
                log("OBS not connected, notification dropped:", notification.message);
                return false;
            }
            if (!await claim(notification.id)) {
                log("already relayed by another client:", notification.message);
                return false;
            }
            try {
                await sendAs("CUSTOM_CHAT_MESSAGE", {
                    message: notification.message,
                    style: notification.style,
                    icon: notification.icon,
                    user: notification.authorName ? { name: notification.authorName } : undefined,
                    actions: (notification.actions ?? []).map(({ label, actionType, url, message, theme }) =>
                        ({ label, actionType, url, message, theme })),
                });
                log("relayed:", notification.message);
                return true;
            } catch (error) {
                log("relay failed", error);
                return false;
            }
        },
    };

    document.addEventListener("catapult:twitchat:notify", (event) => client.relay(event.detail));

    return client;
})();
