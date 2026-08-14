(function (global) {
    "use strict";

    async function sha256Base64(input) {
        const bytes = new TextEncoder().encode(input);
        const digest = await crypto.subtle.digest("SHA-256", bytes);
        return btoa(String.fromCharCode(...new Uint8Array(digest)));
    }

    async function buildAuthResponse(password, salt, challenge) {
        const secret = await sha256Base64(password + salt);
        return sha256Base64(secret + challenge);
    }

    // `onDisconnect` (optional) fires once, only if the socket closes AFTER a successful
    // Identify — i.e. it signals "we were connected and just dropped", not "the initial
    // attempt failed" (that path rejects the returned promise instead). Callers use it to
    // detect a live connection going away (OBS restart, network blip, etc.) so they can
    // reconnect — this function itself never retries, by design (one attempt in, one
    // outcome out; retry policy belongs to the caller).
    function connect({ host, port, password, onDisconnect }) {
        return new Promise((resolve, reject) => {
            const socket = new WebSocket("ws://" + host + ":" + port);
            const pending = new Map();
            const listeners = new Map();
            let requestCounter = 0;
            let settled = false;
            let connected = false;
            let manuallyClosed = false;

            function settle(fn, value) {
                if (settled) return;
                settled = true;
                fn(value);
            }

            socket.onerror = (err) => settle(reject, err);
            // OBS closes cleanly (no error event) when Identify is rejected, e.g. wrong
            // password — without this the connect() promise would never settle.
            socket.onclose = () => {
                settle(reject, new Error("connection closed"));
                for (const { rej } of pending.values()) {
                    rej(new Error("connection closed"));
                }
                pending.clear();
                // Suppressed on an intentional close() — the caller already knows it's
                // gone (it asked for that) and is about to open a replacement itself;
                // firing onDisconnect here would trigger a redundant reconnect race.
                if (connected && !manuallyClosed && typeof onDisconnect === "function") {
                    onDisconnect();
                }
            };

            socket.onmessage = async (event) => {
                const frame = JSON.parse(event.data);
                if (frame.op === 0) {
                    const identify = { op: 1, d: { rpcVersion: 1 } };
                    const auth = frame.d.authentication;
                    if (auth && password) {
                        identify.d.authentication = await buildAuthResponse(password, auth.salt, auth.challenge);
                    }
                    socket.send(JSON.stringify(identify));
                } else if (frame.op === 2) {
                    connected = true;
                    settle(resolve, {
                        call(requestType, requestData) {
                            return new Promise((res, rej) => {
                                const requestId = "req-" + (++requestCounter);
                                pending.set(requestId, { res, rej });
                                socket.send(JSON.stringify({
                                    op: 6,
                                    d: { requestType, requestId, requestData }
                                }));
                            });
                        },
                        // OBS-websocket events (op 5) — e.g. "CustomEvent", broadcast by any
                        // client (including Twitchat itself) via BroadcastCustomEvent and
                        // echoed back to every connected client, ourselves included.
                        on(eventType, handler) {
                            if (!listeners.has(eventType)) {
                                listeners.set(eventType, new Set());
                            }
                            listeners.get(eventType).add(handler);
                        },
                        off(eventType, handler) {
                            const handlers = listeners.get(eventType);
                            if (handlers) handlers.delete(handler);
                        },
                        close() {
                            manuallyClosed = true;
                            socket.close();
                        }
                    });
                } else if (frame.op === 7) {
                    const requestId = frame.d.requestId;
                    const waiting = pending.get(requestId);
                    if (!waiting) return;
                    pending.delete(requestId);
                    if (frame.d.requestStatus && frame.d.requestStatus.result) {
                        waiting.res(frame.d.responseData);
                    } else {
                        waiting.rej(frame.d.requestStatus);
                    }
                } else if (frame.op === 5) {
                    const handlers = listeners.get(frame.d.eventType);
                    if (handlers) {
                        handlers.forEach((h) => h(frame.d.eventData));
                    }
                }
            };
        });
    }

    global.obsWsConnect = connect;
})(window);
