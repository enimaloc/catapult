/**
 * Client of Twitchat's public API, through the page's OBS connection (obs.js): Twitchat talks
 * over obs-websocket custom events, { origin: "twitchat", id, type, data }, which OBS echoes to
 * every connected client. Names and answers come from twitchat-protocol.js (generated from
 * Twitchat's source); IDE typings from src/types/twitchat.d.ts.
 *
 * Twitchat speaks one of two protocols, same wire format but other names: "stable" (its main
 * branch: CHAT_FEED_PAUSE, TRIGGER_LIST…) or "beta" (SET_CHAT_FEED_PAUSE_STATE, ON_TRIGGER_LIST…).
 * Which one is detected from the events it sends, or by asking it before the first action.
 *
 * - actions.<name>(data) / send(ACTION, data): sends an action; a "get" one resolves with the
 *   data of Twitchat's answer (TRIGGERS_GET_ALL -> TRIGGER_LIST's), the others once sent.
 * - on / off / once: Twitchat's events, and actions other clients send ("*" for all of them).
 * - relay(notification): Catapult's notifications, posted in Twitchat's chat. Each arrives as a
 *   "catapult:twitchat:notify" event on document, its detail being the TwitchatNotification.
 */
window.CatapultTwitchat = (function () {
    const PROTOCOLS = CatapultTwitchatProtocol;
    // Assumed until detected: what the public Twitchat speaks.
    const DEFAULT_PROTOCOL = "stable";
    // A "get" action per protocol, unknown to the others: whichever gets answered names Twitchat's.
    const PROBES = { stable: "GET_COLS_COUNT", beta: "GET_GLOBAL_STATES" };
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

    // The protocol of the Twitchat on the other end, null until detected; forced by useProtocol().
    let protocol = null;
    let forced = false;
    const current = () => PROTOCOLS[protocol ?? DEFAULT_PROTOCOL];
    const knowsAction = (name, action) => PROTOCOLS[name].actions.includes(action);

    function setProtocol(name, reason) {
        if (protocol === name) return;
        protocol = name;
        log(`speaking the ${name} protocol (${reason})`);
    }

    /**
     * Detects the protocol from what Twitchat sends: an event only one protocol has. Never from
     * actions, which other clients send too (their probes asking for both protocols, among them).
     */
    function detectFrom(type) {
        if (forced) return;
        const speakers = Object.keys(PROTOCOLS).filter((name) => PROTOCOLS[name].events.includes(type));
        if (speakers.length === 1) setProtocol(speakers[0], `received ${type}`);
    }

    function emit(type, envelope) {
        listeners.get(type)?.forEach((handler) => handler(envelope.data ?? {}, envelope));
    }

    CatapultObs.on("CustomEvent", (eventData) => {
        if (eventData?.origin !== "twitchat" || !eventData.type) return;
        if (eventData.id && sentIds.has(eventData.id)) return;
        detectFrom(eventData.type);
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

    let detecting = null;

    /**
     * Asks Twitchat which protocol it speaks: sends every probe at once, the answer being
     * detected by detectFrom(). Resolves with the protocol, null without an answer in time
     * (Twitchat closed, or not connected to this OBS).
     */
    function detectProtocol({ timeout = DEFAULT_TIMEOUT_MS } = {}) {
        if (protocol || !CatapultObs.isConnected()) return Promise.resolve(protocol);
        detecting ??= new Promise((resolve) => {
            const answers = Object.entries(PROBES).flatMap(([name, probe]) => PROTOCOLS[name].replies[probe]);
            const unsubscribes = answers.map((type) => client.on(type, () => done()));
            const timer = setTimeout(done, timeout);
            function done() {
                clearTimeout(timer);
                unsubscribes.forEach((unsubscribe) => unsubscribe());
                detecting = null;
                resolve(protocol);
            }
            Object.values(PROBES).forEach((probe) => broadcast(probe).catch((error) => log(`${probe} probe failed`, error)));
        });
        return detecting;
    }

    function unknownAction(action, known, style, inProtocol) {
        const suggestion = CatapultSuggest.closest(action, known);
        return new TwitchatError(`${style(action)} isn't a ${inProtocol ? `${inProtocol} ` : ""}Twitchat action`
            + (suggestion ? `, did you mean ${style(suggestion)}?` : ""), { code: "UNKNOWN_ACTION", action, suggestion });
    }

    /** send(), with action names in the errors written by `style` (shortcut or not). */
    async function sendAs(action, data, { timeout = DEFAULT_TIMEOUT_MS } = {}, style = (name) => name) {
        if (!Object.keys(PROTOCOLS).some((name) => knowsAction(name, action))) {
            throw unknownAction(action, current().actions, style, protocol);
        }
        if (!CatapultObs.isConnected()) {
            throw new TwitchatError(`Can't send ${action}: OBS isn't connected`, { code: "NOT_CONNECTED", action });
        }
        // Not awaiting a known protocol: listening for the answer must precede sending, synchronously.
        // Without an answer to the probes, sent in the default protocol all the same: Twitchat may
        // just be slow to answer, or open later.
        const name = protocol ?? await detectProtocol({ timeout }) ?? DEFAULT_PROTOCOL;
        if (!knowsAction(name, action)) throw unknownAction(action, PROTOCOLS[name].actions, style, name);
        const answers = PROTOCOLS[name].replies[action];
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

    // Per protocol, a Proxy whose target has one real property per action of that protocol, for
    // the console's autocompletion; any other name still goes through send()'s checks.
    const actionProxies = Object.fromEntries(Object.entries(PROTOCOLS).map(([name, { actions }]) => [name, new Proxy(
        Object.fromEntries(actions.map((action) => [shortcutName(action), shortcut(shortcutName(action))])), {
            get(_, shortcutKey) {
                if (typeof shortcutKey !== "string" || NOT_ACTIONS.has(shortcutKey)) return undefined;
                return shortcut(shortcutKey);
            },
        })]));

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
        PROTOCOLS,
        TwitchatError,

        /** The protocol of the Twitchat on the other end of OBS, null until detected. */
        get protocol() {
            return protocol;
        },

        /** This Twitchat's actions, events and answers: the default protocol's until detected. */
        get ACTIONS() {
            return current().actions;
        },
        get EVENTS() {
            return current().events;
        },
        get REPLIES() {
            return current().replies;
        },

        /**
         * One shortcut per action: actions.chatFeedPause() is send("CHAT_FEED_PAUSE"), and
         * actions.triggersGetAll() resolves with TRIGGER_LIST's data. An unknown one rejects with
         * the closest known shortcut suggested. Lists this Twitchat's actions.
         */
        get actions() {
            return actionProxies[protocol ?? DEFAULT_PROTOCOL];
        },

        /** Whether Twitchat can be reached, i.e. the page is connected to OBS. */
        isConnected() {
            return CatapultObs.isConnected();
        },

        detectProtocol,

        /** Forces the protocol, e.g. for a Twitchat that never answers the probes. */
        useProtocol(name) {
            if (!PROTOCOLS[name]) throw new TypeError(`Unknown Twitchat protocol ${name}: ${Object.keys(PROTOCOLS).join(" or ")}`);
            forced = true;
            setProtocol(name, "forced");
        },

        /**
         * Applies the branch picked in the integration settings: "auto" detects it (the default
         * protocol when Twitchat doesn't answer), a protocol name forces it.
         */
        configure(branch) {
            if (branch !== "auto") return client.useProtocol(branch);
            if (!forced) return;
            forced = false;
            protocol = null;
            log("detecting the protocol");
            detectProtocol();
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
    // Another OBS may have another Twitchat: detected anew, right away so actions lists its own.
    document.addEventListener("catapult:obs:connected", () => {
        if (forced) return;
        protocol = null;
        detectProtocol();
    });

    return client;
})();
