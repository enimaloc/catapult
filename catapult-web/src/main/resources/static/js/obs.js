/**
 * Single obs-websocket (v5, rpcVersion 1) connection shared by the whole page. Unlike
 * catapult-web-old's obsWsConnect, which handed back a new handle per connection, this is a
 * singleton: callers talk to CatapultObs directly, and event listeners survive reconnects, so a
 * feature subscribes once at load instead of after every connect().
 *
 * Covers the whole JSON protocol: Hello/Identify (with auth), Reidentify, events, requests and
 * request batches. The msgpack subprotocol isn't supported (it would need a decoder library and
 * buys nothing at the volumes a browser tab handles).
 */
window.CatapultObs = (function () {
    const RPC_VERSION = 1;

    const OpCode = Object.freeze({
        Hello: 0,
        Identify: 1,
        Identified: 2,
        Reidentify: 3,
        Event: 5,
        Request: 6,
        RequestResponse: 7,
        RequestBatch: 8,
        RequestBatchResponse: 9,
    });

    /** Bitmask for Identify/Reidentify's eventSubscriptions. */
    const EventSubscription = (function () {
        const flags = {
            None: 0,
            General: 1 << 0,
            Config: 1 << 1,
            Scenes: 1 << 2,
            Inputs: 1 << 3,
            Transitions: 1 << 4,
            Filters: 1 << 5,
            Outputs: 1 << 6,
            SceneItems: 1 << 7,
            MediaInputs: 1 << 8,
            Vendors: 1 << 9,
            Ui: 1 << 10,
            Canvases: 1 << 11,
            // High-volume events, left out of All: they must be asked for explicitly.
            InputVolumeMeters: 1 << 16,
            InputActiveStateChanged: 1 << 17,
            InputShowStateChanged: 1 << 18,
            SceneItemTransformChanged: 1 << 19,
        };
        flags.All = flags.General | flags.Config | flags.Scenes | flags.Inputs | flags.Transitions
            | flags.Filters | flags.Outputs | flags.SceneItems | flags.MediaInputs | flags.Vendors | flags.Ui
            | flags.Canvases;
        return Object.freeze(flags);
    })();

    const RequestBatchExecutionType = Object.freeze({
        None: -1,
        SerialRealtime: 0,
        SerialFrame: 1,
        Parallel: 2,
    });

    /** Codes OBS closes the socket with, named in the ObsError message. */
    const CloseCode = Object.freeze({
        UnknownReason: 4000,
        MessageDecodeError: 4002,
        MissingDataField: 4003,
        InvalidDataFieldType: 4004,
        InvalidDataFieldValue: 4005,
        UnknownOpCode: 4006,
        NotIdentified: 4007,
        AlreadyIdentified: 4008,
        AuthenticationFailed: 4009,
        UnsupportedRpcVersion: 4010,
        SessionInvalidated: 4011,
        UnsupportedFeature: 4012,
    });

    /** OBS's request status for a request type it doesn't know. */
    const UNKNOWN_REQUEST_TYPE = 204;

    /**
     * Every failure CatapultObs rejects with. `code` is the close code for a closed connection,
     * the request status code for a failed request (`requestType`/`comment` set then too, and
     * `suggestion`, the closest known request type, when the type was unknown).
     */
    class ObsError extends Error {
        constructor(message, { code = null, requestType = null, comment = null, suggestion = null } = {}) {
            super(message);
            this.name = "ObsError";
            this.code = code;
            this.requestType = requestType;
            this.comment = comment;
            this.suggestion = suggestion;
        }
    }

    function closeError(code, reason) {
        const name = Object.keys(CloseCode).find((key) => CloseCode[key] === code);
        const label = name ?? (code ? `code ${code}` : "no code");
        return new ObsError(`Connection closed (${label})${reason ? `: ${reason}` : ""}`, { code: code ?? null });
    }

    function requestError(requestType, status) {
        const message = `${requestType} failed (${status?.code})${status?.comment ? `: ${status.comment}` : ""}`;
        return new ObsError(message, { code: status?.code ?? null, requestType, comment: status?.comment ?? null });
    }

    let socket = null;
    let password = null;
    let eventSubscriptions;
    let pending = new Map();
    const listeners = new Map();
    let requestCounter = 0;
    let settle = () => {};
    let identifiedWaiters = [];
    let connected = false;
    let info = null;
    // The request types the connected OBS knows (GetVersion's availableRequests); null when
    // unknown, which leaves OBS to judge request types itself.
    let available = null;

    /** Case-insensitive Levenshtein distance. */
    function distance(a, b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        let previous = Array.from({ length: b.length + 1 }, (_, j) => j);
        for (let i = 1; i <= a.length; i++) {
            const current = [i];
            for (let j = 1; j <= b.length; j++) {
                current[j] = Math.min(previous[j] + 1, current[j - 1] + 1,
                    previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
            }
            previous = current;
        }
        return previous[b.length];
    }

    /** The known request type closest to requestType, when near enough to be a typo of it. */
    function closestRequestType(requestType) {
        let closest = null;
        let closestDistance = Infinity;
        for (const candidate of available) {
            const d = distance(requestType, candidate);
            if (d < closestDistance) {
                closest = candidate;
                closestDistance = d;
            }
        }
        return closestDistance <= Math.max(2, Math.floor(requestType.length / 4)) ? closest : null;
    }

    /**
     * The ObsError for a request type the connected OBS doesn't know, null when it knows it (or
     * its list is unknown). `style` writes the names the way the caller did (shortcut or not).
     */
    function unknownRequestType(requestType, style = (name) => name) {
        if (!available || available.has(requestType)) return null;
        const suggestion = closestRequestType(requestType);
        const message = `${style(requestType)} isn't a request this OBS knows`
            + (suggestion ? `, did you mean ${style(suggestion)}?` : "");
        return new ObsError(message, { code: UNKNOWN_REQUEST_TYPE, requestType, suggestion });
    }

    /** call(), with request type names in the errors written by `style`. */
    function request(requestType, requestData, style) {
        const unknown = unknownRequestType(requestType, style);
        if (unknown) return Promise.reject(unknown);
        const d = requestData === undefined ? { requestType } : { requestType, requestData };
        return sendAwaiting(OpCode.Request, d, (answer, resolve, reject) => {
            if (answer.requestStatus?.result) {
                resolve(answer.responseData ?? {});
            } else {
                reject(requestError(requestType, answer.requestStatus));
            }
        });
    }

    async function sha256Base64(input) {
        const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
        return btoa(String.fromCharCode(...new Uint8Array(digest)));
    }

    async function buildAuthResponse(salt, challenge) {
        return sha256Base64(await sha256Base64(password + salt) + challenge);
    }

    function send(op, d) {
        socket.send(JSON.stringify({ op, d }));
    }

    /** Sends a frame whose answer comes back with the same requestId, handed to onAnswer. */
    function sendAwaiting(op, d, onAnswer) {
        if (!connected) return Promise.reject(new ObsError("Not connected"));
        d.requestId = `req-${++requestCounter}`;
        return new Promise((resolve, reject) => {
            pending.set(d.requestId, { resolve: (answer) => onAnswer(answer, resolve, reject), reject });
            send(op, d);
        });
    }

    function failPending(error) {
        for (const { reject } of pending.values()) reject(error);
        pending.clear();
        identifiedWaiters.forEach(({ reject }) => reject(error));
        identifiedWaiters = [];
    }

    /**
     * Page-wide connection state, for scripts that don't own the connection (obs-session.js
     * does): "catapult:obs:connected" ({ info }) once connect() resolved, ready for requests,
     * "catapult:obs:disconnected" ({ error }) when that connection ended, close() included.
     */
    function dispatch(type, detail) {
        document.dispatchEvent(new CustomEvent(type, { detail }));
    }

    function emit(eventType, event) {
        listeners.get(eventType)?.forEach((handler) => handler(event.eventData ?? {}, event));
    }

    const obs = {
        OpCode,
        EventSubscription,
        RequestBatchExecutionType,
        CloseCode,
        ObsError,

        /**
         * Opens the connection and resolves with info() once OBS accepted the Identify. Rejects
         * with an ObsError if the socket closes before that, e.g. code AuthenticationFailed on a
         * wrong password. Never retries: retry policy belongs to the caller, see onDisconnect.
         *
         * @param {object} options
         * @param {string} options.host
         * @param {number} options.port
         * @param {string} [options.password]
         * @param {boolean} [options.secure] wss:// instead of ws:// (OBS behind a TLS proxy)
         * @param {number} [options.eventSubscriptions] EventSubscription bitmask, OBS's default
         *     (All) when left out
         */
        connect({ host, port, password: pwd, secure = false, eventSubscriptions: subscriptions }) {
            if (socket) obs.close();
            password = pwd || null;
            eventSubscriptions = subscriptions;
            pending = new Map();
            identifiedWaiters = [];
            connected = false;
            info = null;
            available = null;
            listShortcuts([]);
            return new Promise((resolve, reject) => {
                let settled = false;
                settle = (ok, value) => {
                    if (settled) return;
                    settled = true;
                    (ok ? resolve : reject)(value);
                };
                const current = new WebSocket(`${secure ? "wss" : "ws"}://${host}:${port}`, "obswebsocket.json");
                socket = current;
                // Once close() or a newer connect() replaced it, this socket's late events are
                // ignored, so they can't settle or fail the next connection's promises. Errors
                // need no handler: a close event, with its code, always follows them.
                current.onclose = (event) => {
                    if (socket !== current) return;
                    socket = null;
                    const error = closeError(event?.code, event?.reason);
                    const wasConnected = connected;
                    connected = false;
                    settle(false, error);
                    failPending(error);
                    if (wasConnected) {
                        obs.onDisconnect(error);
                        dispatch("catapult:obs:disconnected", { error });
                    }
                };
                current.onmessage = (event) => socket === current && obs.onMessage(JSON.parse(event.data));
            });
        },

        /**
         * Overridable hook: a live connection (past Identify) dropped without close(), with the
         * ObsError telling why (code SessionInvalidated when OBS kicked this client, etc.).
         */
        onDisconnect() {},

        isConnected() {
            return connected;
        },

        /**
         * What OBS announced for the current connection, null before the Hello:
         * { obsWebSocketVersion, rpcVersion, authenticated, negotiatedRpcVersion, availableRequests }.
         */
        info() {
            return info;
        },

        async onMessage(frame) {
            const d = frame.d ?? {};
            switch (frame.op) {
                case OpCode.Hello: {
                    const current = socket;
                    info = { obsWebSocketVersion: d.obsWebSocketVersion, rpcVersion: d.rpcVersion, authenticated: !!d.authentication };
                    const identify = { rpcVersion: RPC_VERSION };
                    if (eventSubscriptions !== undefined) identify.eventSubscriptions = eventSubscriptions;
                    if (d.authentication && password) {
                        identify.authentication = await buildAuthResponse(d.authentication.salt, d.authentication.challenge);
                    }
                    // The socket may have been replaced while hashing.
                    if (socket === current) send(OpCode.Identify, identify);
                    break;
                }
                case OpCode.Identified: {
                    info = { ...info, negotiatedRpcVersion: d.negotiatedRpcVersion };
                    if (connected) { // OBS's answer to a Reidentify
                        identifiedWaiters.forEach(({ resolve }) => resolve(info));
                        identifiedWaiters = [];
                        break;
                    }
                    const current = socket;
                    connected = true;
                    // Before connect() resolves, so request types are checked from the first call.
                    // Without the list (GetVersion failed), OBS keeps judging them itself.
                    try {
                        const { availableRequests } = await obs.call("GetVersion");
                        if (socket !== current) return;
                        if (Array.isArray(availableRequests)) {
                            available = new Set(availableRequests);
                            info = { ...info, availableRequests };
                            listShortcuts(availableRequests);
                        }
                    } catch {
                        if (socket !== current) return;
                    }
                    settle(true, info);
                    dispatch("catapult:obs:connected", { info });
                    break;
                }
                case OpCode.Event:
                    emit(d.eventType, d);
                    emit("*", d);
                    break;
                case OpCode.RequestResponse:
                case OpCode.RequestBatchResponse: {
                    const waiting = pending.get(d.requestId);
                    if (!waiting) return;
                    pending.delete(d.requestId);
                    waiting.resolve(d);
                    break;
                }
            }
        },

        /**
         * Sends a request, resolving with its responseData ({} when OBS sends none), rejecting
         * with an ObsError carrying the request status code and comment when it fails. A request
         * type the connected OBS doesn't know fails without being sent, with the closest known
         * one as `suggestion`.
         */
        call(requestType, requestData) {
            return request(requestType, requestData);
        },

        /**
         * Sends several requests as one RequestBatch. Resolves with one entry per request that
         * ran, in order: { requestType, ok: true, data } or { requestType, ok: false, error }. A
         * failed request never rejects the batch, only a lost connection does. With
         * haltOnFailure, OBS stops at the first failure and the later requests are absent.
         *
         * @param {Array<{requestType: string, requestData?: object}>} requests
         * @param {object} [options]
         * @param {boolean} [options.haltOnFailure]
         * @param {number} [options.executionType] RequestBatchExecutionType, SerialRealtime by default
         */
        callBatch(requests, { haltOnFailure, executionType } = {}) {
            // A typo is a bug in the batch, not an outcome of one of its requests: nothing is sent.
            const unknown = requests.map(({ requestType }) => unknownRequestType(requestType)).find(Boolean);
            if (unknown) return Promise.reject(unknown);
            const d = {
                requests: requests.map(({ requestType, requestData }) =>
                    (requestData === undefined ? { requestType } : { requestType, requestData })),
            };
            if (haltOnFailure !== undefined) d.haltOnFailure = haltOnFailure;
            if (executionType !== undefined) d.executionType = executionType;
            return sendAwaiting(OpCode.RequestBatch, d, (answer, resolve) => {
                resolve((answer.results ?? []).map(({ requestType, requestStatus, responseData }) => (requestStatus?.result
                    ? { requestType, ok: true, data: responseData ?? {} }
                    : { requestType, ok: false, error: requestError(requestType, requestStatus) })));
            });
        },

        /**
         * Changes the live connection's event subscriptions, resolving with info() once OBS
         * confirmed. Only for this connection: a later connect() uses its own options again.
         */
        reidentify(subscriptions) {
            if (!connected) return Promise.reject(new ObsError("Not connected"));
            return new Promise((resolve, reject) => {
                identifiedWaiters.push({ resolve, reject });
                send(OpCode.Reidentify, subscriptions === undefined ? {} : { eventSubscriptions: subscriptions });
            });
        },

        /**
         * Subscribes to an event type, or "*" for all of them. The handler gets the eventData and
         * the whole event ({ eventType, eventIntent, eventData }). Returns the unsubscribe function.
         */
        on(eventType, handler) {
            if (!listeners.has(eventType)) listeners.set(eventType, new Set());
            listeners.get(eventType).add(handler);
            return () => obs.off(eventType, handler);
        },

        off(eventType, handler) {
            listeners.get(eventType)?.delete(handler);
        },

        /** Resolves with the eventData of the next eventType event. */
        once(eventType) {
            return new Promise((resolve) => {
                const unsubscribe = obs.on(eventType, (eventData) => {
                    unsubscribe();
                    resolve(eventData);
                });
            });
        },

        /** Closes on purpose: pending requests fail, but onDisconnect doesn't fire. */
        close() {
            if (!socket) return;
            const closing = socket;
            const wasConnected = connected;
            socket = null;
            connected = false;
            const error = new ObsError("Connection closed", { code: 1000 });
            settle(false, error);
            failPending(error);
            closing.close(1000);
            if (wasConnected) dispatch("catapult:obs:disconnected", { error });
        },
    };

    // Read on any object by await, JSON.stringify, test matchers…: never OBS request names, and
    // turning them into requests would make `await obs.requests` send a "Then" request.
    const NOT_REQUESTS = new Set(["then", "catch", "finally", "toJSON", "constructor", "asymmetricMatch", "nodeType", "$$typeof"]);
    const shortcuts = new Map();
    const shortcutName = (requestType) => requestType[0].toLowerCase() + requestType.slice(1);

    function shortcut(name) {
        if (!shortcuts.has(name)) {
            const requestType = name[0].toUpperCase() + name.slice(1);
            shortcuts.set(name, (requestData) => request(requestType, requestData, shortcutName));
        }
        return shortcuts.get(name);
    }

    // The Proxy's target: one real property per request the connected OBS knows, only there for
    // the browser console's autocompletion to list (the Proxy answers any name regardless).
    const listedShortcuts = {};

    /** Lists the shortcuts of these request types, replacing the previous connection's. */
    function listShortcuts(requestTypes) {
        for (const name of Object.keys(listedShortcuts)) delete listedShortcuts[name];
        for (const requestType of requestTypes) {
            listedShortcuts[shortcutName(requestType)] = shortcut(shortcutName(requestType));
        }
    }

    /**
     * Shortcuts for every request: requests.getSceneList() is call("GetSceneList"), and
     * requests.setCurrentProgramScene({ sceneName }) is call("SetCurrentProgramScene", { sceneName }).
     * Built on access, so any request OBS knows works without being listed here; an unknown one
     * rejects with status 204 (UnknownRequestType) and the closest known shortcut suggested.
     * Typed for IDEs by src/types/obs.d.ts.
     */
    obs.requests = new Proxy(listedShortcuts, {
        get(_, name) {
            if (typeof name !== "string" || NOT_REQUESTS.has(name)) return undefined;
            return shortcut(name);
        },
    });

    return obs;
})();
