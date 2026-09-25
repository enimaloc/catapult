/**
 * Live updates for /channel/{username}: a WebSocket connection that reacts to a push by
 * calling the exact same CatapultChannel.refresh() every mutation on this page already
 * calls on success — so a change made in one tab (or, later, by the real backend) shows up
 * in every other tab watching the same channel without the viewer doing anything.
 *
 * The server never sends rendered content over the socket, only {"scope": "..."} — the
 * scope is informational for now; today every push just triggers the same full refresh an
 * action in this tab would.
 */
(function () {
    let socket = null;
    let socketUsername = null;
    let reconnectTimer = null;

    function currentChannelUsername() {
        const segments = location.pathname.split("/");
        return segments[1] === "channel" && segments[2] ? segments[2] : null;
    }

    function closeSocket() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        if (socket) {
            socket.onclose = null;
            socket.close();
            socket = null;
        }
        socketUsername = null;
    }

    function connect(username) {
        if (socket && socketUsername === username && socket.readyState <= WebSocket.OPEN) {
            return;
        }
        closeSocket();
        socketUsername = username;

        const protocol = location.protocol === "https:" ? "wss:" : "ws:";
        socket = new WebSocket(`${protocol}//${location.host}/ws/channel/${username}`);

        socket.addEventListener("message", () => {
            CatapultChannel.refresh();
        });

        socket.addEventListener("close", () => {
            if (socketUsername === username) {
                reconnectTimer = setTimeout(() => connect(username), 3000);
            }
        });
    }

    document.addEventListener("catapult:render", () => {
        const username = currentChannelUsername();
        if (username) {
            connect(username);
        } else {
            closeSocket();
        }
    });
})();
