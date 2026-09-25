/**
 * Live updates for /channel/{username}: a Server-Sent Events stream that reacts to a push by
 * calling the exact same CatapultChannel.refresh() every mutation on this page already calls
 * on success — so a change made in one tab (or, later, by the real backend) shows up in every
 * other tab watching the same channel without the viewer doing anything.
 *
 * EventSource reconnects on its own (no manual retry logic needed here), and the stream
 * carries a bare "update" event with no payload — the server never sends rendered content,
 * the client just re-fetches and patches via refresh().
 */
(function () {
    let source = null;
    let sourceUsername = null;

    function currentChannelUsername() {
        const segments = location.pathname.split("/");
        return segments[1] === "channel" && segments[2] ? segments[2] : null;
    }

    function closeSource() {
        if (source) {
            source.close();
            source = null;
        }
        sourceUsername = null;
    }

    function connect(username) {
        if (source && sourceUsername === username) {
            return;
        }
        closeSource();
        sourceUsername = username;

        source = new EventSource(`/events/channel/${username}`);
        source.addEventListener("update", () => {
            CatapultChannel.refresh();
        });
    }

    document.addEventListener("catapult:render", () => {
        const username = currentChannelUsername();
        if (username) {
            connect(username);
        } else {
            closeSource();
        }
    });
})();
