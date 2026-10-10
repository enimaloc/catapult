/**
 * Single obs-websocket (v5) connection shared by the whole page. Unlike catapult-web-old's
 * obsWsConnect, which handed back a new handle per connection, this is a singleton: callers
 * talk to CatapultObs directly, and event listeners survive reconnects, so a feature
 * subscribes once at load instead of after every connect().
 */
window.CatapultObs = (function () {
    let socket = null;
    let password = null;
    let pending = new Map();
    const listeners = new Map();
    let requestCounter = 0;
    let settle = () => {};
    let connected = false;

    async function sha256Base64(input) {
        const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
        return btoa(String.fromCharCode(...new Uint8Array(digest)));
    }

    async function buildAuthResponse(salt, challenge) {
        return sha256Base64(await sha256Base64(password + salt) + challenge);
    }

    function failPending(reason) {
        for (const { reject } of pending.values()) reject(reason);
        pending.clear();
    }

    const obs = {
        /**
         * Opens the connection and resolves once OBS accepted the Identify, rejects if the socket
         * errors or closes before that (e.g. wrong password: OBS closes without an error event).
         * Never retries: retry policy belongs to the caller, see onDisconnect.
         */
        connect({ host, port, password: pwd }) {
            if (socket) obs.close();
            password = pwd || null;
            pending = new Map();
            connected = false;
            return new Promise((resolve, reject) => {
                let settled = false;
                settle = (ok, value) => {
                    if (settled) return;
                    settled = true;
                    (ok ? resolve : reject)(value);
                };
                const current = new WebSocket(`ws://${host}:${port}`);
                socket = current;
                // Once close() or a newer connect() replaced it, this socket's late events are
                // ignored, so they can't settle or fail the next connection's promises.
                current.onerror = (err) => socket === current && settle(false, err);
                current.onclose = () => {
                    if (socket !== current) return;
                    socket = null;
                    settle(false, new Error("Connection closed"));
                    failPending(new Error("Connection closed"));
                    if (connected) {
                        connected = false;
                        obs.onDisconnect();
                    }
                };
                current.onmessage = (event) => socket === current && obs.onMessage(JSON.parse(event.data));
            });
        },

        /** Overridable hook: a live connection (past Identify) dropped without close(). */
        onDisconnect() {},

        isConnected() {
            return connected;
        },

        async onMessage(frame) {
            switch (frame.op) {
                case 0: { // Hello -> Identify, answering the auth challenge when OBS sets one
                    const identify = { op: 1, d: { rpcVersion: 1 } };
                    const auth = frame.d.authentication;
                    if (auth && password) {
                        identify.d.authentication = await buildAuthResponse(auth.salt, auth.challenge);
                    }
                    socket?.send(JSON.stringify(identify));
                    break;
                }
                case 2: // Identified
                    connected = true;
                    settle(true);
                    break;
                case 5: // Event
                    listeners.get(frame.d.eventType)?.forEach((handler) => handler(frame.d.eventData));
                    break;
                case 7: { // RequestResponse
                    const waiting = pending.get(frame.d.requestId);
                    if (!waiting) return;
                    pending.delete(frame.d.requestId);
                    if (frame.d.requestStatus?.result) {
                        waiting.resolve(frame.d.responseData);
                    } else {
                        waiting.reject(frame.d.requestStatus);
                    }
                    break;
                }
            }
        },

        /** Sends an obs-websocket request, resolving with its responseData. */
        call(requestType, requestData) {
            if (!connected) return Promise.reject(new Error("Not connected"));
            return new Promise((resolve, reject) => {
                const requestId = `req-${++requestCounter}`;
                pending.set(requestId, { resolve, reject });
                socket.send(JSON.stringify({ op: 6, d: { requestType, requestId, requestData } }));
            });
        },

        on(eventType, handler) {
            if (!listeners.has(eventType)) listeners.set(eventType, new Set());
            listeners.get(eventType).add(handler);
        },

        off(eventType, handler) {
            listeners.get(eventType)?.delete(handler);
        },

        /** Closes on purpose: pending requests fail, but onDisconnect doesn't fire. */
        close() {
            if (!socket) return;
            const closing = socket;
            socket = null;
            connected = false;
            settle(false, new Error("Connection closed"));
            failPending(new Error("Connection closed"));
            closing.close();
        },
    };

    return obs;
})();
