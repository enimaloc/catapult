/*
 * The page's single live stream (/events/nav, NavEventStreamController): every script listens
 * here rather than opening its own, a browser allowing only a few connections per site.
 *
 * EventSource reconnects by itself, except when a reconnect is answered with something other
 * than a stream (a maintenance page during a restart, a 503 when the server is full): it
 * then stops for good, so a closed source is rebuilt after a backoff (5 s, doubling up to 60 s,
 * back to 5 s once a stream opens). A stream the server closed for a newer one of the same tab
 * group ("Evicted") is not reopened until this tab is shown again, else the tabs would keep
 * evicting each other.
 */
window.CatapultLive = (function () {
    const listeners = new Map();
    const reopen = [];
    const opened = [];
    if (typeof EventSource === "undefined") {
        return { on() {}, onReopen() {}, onOpen() {} };
    }
    const FIRST_DELAY = 5000;
    const MAX_DELAY = 60000;
    let source = null;
    let first = true;
    let delay = FIRST_DELAY;
    let retry = null;
    let evicted = false;

    function call(listener, data) {
        try { listener(data); } catch (e) { console.error(e); }
    }
    function dispatcher(name) {
        return event => {
            let data;
            try { data = JSON.parse(event.data); } catch { return; }
            listeners.get(name).forEach(l => call(l, data));
        };
    }
    function connect() {
        retry = null;
        const current = new EventSource("/events/nav");
        source = current;
        current.addEventListener("open", () => {
            delay = FIRST_DELAY;
            opened.forEach(listener => call(listener));
            if (!first) reopen.forEach(listener => call(listener));
            first = false;
        });
        current.addEventListener("error", () => {
            if (current !== source || evicted || current.readyState !== EventSource.CLOSED) return;
            scheduleReconnect();
        });
        current.addEventListener("Evicted", () => {
            if (current !== source) return;
            evicted = true;
            current.close();
        });
        listeners.forEach((ignored, name) => current.addEventListener(name, dispatcher(name)));
    }
    function scheduleReconnect() {
        if (retry !== null) return;
        retry = setTimeout(connect, delay);
        delay = Math.min(delay * 2, MAX_DELAY);
    }
    document.addEventListener("visibilitychange", () => {
        if (!evicted || document.visibilityState !== "visible") return;
        evicted = false;
        connect();
    });
    function on(name, listener) {
        if (!listeners.has(name)) {
            listeners.set(name, []);
            source.addEventListener(name, dispatcher(name));
        }
        listeners.get(name).push(listener);
    }
    connect();
    return { on, onReopen: l => reopen.push(l), onOpen: l => opened.push(l) };
})();
