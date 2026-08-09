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

    function connect({ host, port, password }) {
        return new Promise((resolve, reject) => {
            const socket = new WebSocket("ws://" + host + ":" + port);
            const pending = new Map();
            let requestCounter = 0;

            socket.onerror = (err) => reject(err);

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
                    resolve({
                        call(requestType, requestData) {
                            return new Promise((res, rej) => {
                                const requestId = "req-" + (++requestCounter);
                                pending.set(requestId, { res, rej });
                                socket.send(JSON.stringify({
                                    op: 6,
                                    d: { requestType, requestId, requestData }
                                }));
                            });
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
                }
            };
        });
    }

    global.obsWsConnect = connect;
})(window);
