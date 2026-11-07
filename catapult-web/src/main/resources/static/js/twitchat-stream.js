/**
 * The logged-in owner's Twitchat dispatches (/me/twitchat/events, an owner-only SSE stream:
 * their payloads carry signed action tokens), listened to while the page is connected to OBS,
 * the only way they can reach Twitchat. Each becomes a "catapult:twitchat:dispatch" event on
 * document, sent by twitchat.js. A page can name another stream in its body's
 * data-twitchat-events-url (the OBS widget page: /widget/obs/{token}/events).
 */
(function () {
    // A refused stream (404 once a widget link is revoked, 502 while catapult-api restarts) is
    // left closed by the browser: tried again this often, while OBS stays connected.
    const RETRY_MS = 30000;

    let source = null;
    let retry = null;

    function open() {
        if (source) return;
        clearTimeout(retry);
        const current = new EventSource(document.body?.dataset.twitchatEventsUrl || "/me/twitchat/events");
        source = current;
        current.addEventListener("TwitchatDispatch", (event) => {
            let detail;
            try {
                detail = JSON.parse(event.data);
            } catch (error) {
                console.warn("[twitchat] malformed dispatch", error);
                return;
            }
            document.dispatchEvent(new CustomEvent("catapult:twitchat:dispatch", { detail }));
        });
        // A stream the server ended (the widget's periodic close) is reconnected by the browser itself.
        current.addEventListener("error", () => {
            if (source !== current || current.readyState !== EventSource.CLOSED) return;
            source = null;
            retry = setTimeout(open, RETRY_MS);
        });
    }

    function close() {
        clearTimeout(retry);
        source?.close();
        source = null;
    }

    document.addEventListener("catapult:obs:connected", open);
    document.addEventListener("catapult:obs:disconnected", close);
})();
