/**
 * The logged-in owner's Twitchat dispatches (/me/twitchat/events, an owner-only SSE stream:
 * their payloads carry signed action tokens), listened to while the page is connected to OBS,
 * the only way they can reach Twitchat. Each becomes a "catapult:twitchat:dispatch" event on
 * document, sent by twitchat.js. A page can name another stream in its body's
 * data-twitchat-events-url (the OBS widget page: /widget/obs/{token}/events).
 */
(function () {
    let source = null;

    function open() {
        if (source) return;
        source = new EventSource(document.body?.dataset.twitchatEventsUrl || "/me/twitchat/events");
        source.addEventListener("TwitchatDispatch", (event) => {
            let detail;
            try {
                detail = JSON.parse(event.data);
            } catch (error) {
                console.warn("[twitchat] malformed dispatch", error);
                return;
            }
            document.dispatchEvent(new CustomEvent("catapult:twitchat:dispatch", { detail }));
        });
    }

    function close() {
        source?.close();
        source = null;
    }

    document.addEventListener("catapult:obs:connected", open);
    document.addEventListener("catapult:obs:disconnected", close);
})();
