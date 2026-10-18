/*
 * The page's single live stream (/events/nav, NavEventStreamController): every script listens
 * here rather than opening its own, a browser allowing only a few connections per site.
 */
window.CatapultLive = (function () {
    const listeners = new Map();
    const reopen = [];
    const opened = [];
    if (typeof EventSource === "undefined") {
        return { on() {}, onReopen() {}, onOpen() {} };
    }
    const source = new EventSource("/events/nav");
    let first = true;
    source.addEventListener("open", () => {
        opened.forEach(listener => call(listener));
        if (!first) reopen.forEach(listener => call(listener));
        first = false;
    });
    function call(listener, data) {
        try { listener(data); } catch (e) { console.error(e); }
    }
    function on(name, listener) {
        if (!listeners.has(name)) {
            listeners.set(name, []);
            source.addEventListener(name, event => {
                let data;
                try { data = JSON.parse(event.data); } catch { return; }
                listeners.get(name).forEach(l => call(l, data));
            });
        }
        listeners.get(name).push(listener);
    }
    return { on, onReopen: l => reopen.push(l), onOpen: l => opened.push(l) };
})();
