/**
 * Records the page's OBS and Twitchat exchanges (obs.js, twitchat.js) while its member is in
 * debug mode, and sends them by batches to catapult-web, which relays them to the member's debug
 * journal. Off unless enabled: on a page rendered in debug mode (body data-debug-capture), or by
 * obs-session.js from its config's debugCapture (the widget page's own URL then). Every hook
 * checks active() first, so nothing is built outside debug mode.
 *
 * Secrets never leave the page: the OBS authentication proof and secret settings (stripObs), the
 * signed action tokens of Twitchat messages (stripTwitchat) and the widget token in any string.
 * A batch goes every 5s or at 50 entries; past 300 entries a minute, entries are counted instead,
 * one "dropped" entry per service for that minute. A failed batch is dropped, never sent again;
 * a 409 (the debug mode ended) stops the capture.
 */
window.CatapultDebugCapture = (function () {
    const PAGE_URL = "/me/debug/client-exchanges";
    const FLUSH_MS = 5000;
    const BATCH_ENTRIES = 50;
    const PER_MINUTE = 300;
    const MINUTE_MS = 60000;
    // The debug journal's own bound on a body, which it truncates anyway.
    const MAX_BODY_CHARS = 65536;
    // catapult-api reads batches of up to 256 KiB: some room left for the envelope.
    const MAX_BATCH_BYTES = 250 * 1024;
    // The most requests held for their answer: past it (answers OBS never sent), new ones go uncaptured.
    const MAX_PENDING = 1000;
    const REDACTED = "<redacted>";
    const WIDGET_TOKEN = /\/widget\/obs\/[^/?#"\s\\]+/g;
    // Catapult's signed action tokens are UUIDs, written anywhere in a payload's strings.
    const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
    const SECRET_KEYS = new Set(["password", "key", "streamkey", "stream_key", "secret", "token"]);

    let target = null;
    let queue = [];
    let timer = null;
    const waiting = new Map();
    let windowStart = 0;
    let windowCount = 0;
    let dropped = {};

    function reset() {
        clearTimeout(timer);
        timer = null;
        queue = [];
        waiting.clear();
        windowStart = 0;
        windowCount = 0;
        dropped = {};
    }

    /** A copy of `value` with `map(key, value)` applied to every member, keys and nested values included. */
    function walk(value, map) {
        if (Array.isArray(value)) return value.map((item) => walk(item, map));
        if (!value || typeof value !== "object") return map(null, value);
        const copy = {};
        for (const [key, item] of Object.entries(value)) {
            const mapped = map(key, item);
            if (mapped !== undefined) copy[map(null, key)] = mapped === item ? walk(item, map) : mapped;
        }
        return copy;
    }

    function safely(strip, value) {
        try {
            return strip(value);
        } catch {
            return null;
        }
    }

    /** A Twitchat message (or its data) without the action tokens in its data. */
    function stripTwitchat(message) {
        return safely((value) => {
            const noTokens = (key, item) => (typeof item === "string" ? item.replace(UUID, REDACTED) : item);
            if (!value || typeof value !== "object" || Array.isArray(value)) return walk(value, noTokens);
            const copy = { ...value };
            if ("data" in copy) copy.data = walk(copy.data, noTokens);
            if (Array.isArray(copy.payloads)) copy.payloads = copy.payloads.map(stripTwitchat);
            return copy;
        }, message);
    }

    /** An OBS message without its authentication or secret settings, its Twitchat messages stripped. */
    function stripObs(message) {
        return safely((value) => walk(value, (key, item) => {
            if (key === null) return item;
            if (key === "authentication") return undefined;
            if (SECRET_KEYS.has(key.toLowerCase()) && typeof item === "string") return REDACTED;
            if (item && typeof item === "object" && item.origin === "twitchat") return stripTwitchat(item);
            return item;
        }), message);
    }

    /** A body as sent: a copy, without the widget token, truncated past the journal's bound. */
    function body(value) {
        if (value === null || value === undefined) return null;
        let json;
        try {
            json = JSON.stringify(value).replace(WIDGET_TOKEN, `/widget/obs/${REDACTED}`);
        } catch {
            return { unserializable: true };
        }
        if (json.length <= MAX_BODY_CHARS) return JSON.parse(json);
        return { truncated: `${json.slice(0, MAX_BODY_CHARS)}…(+${json.length - MAX_BODY_CHARS} chars)` };
    }

    const text = (value) => (value === null || value === undefined ? null
        : String(value).replace(WIDGET_TOKEN, `/widget/obs/${REDACTED}`));

    function normalize(entry) {
        return {
            service: entry.service,
            direction: entry.direction,
            operation: text(entry.operation),
            at: entry.at ?? new Date().toISOString(),
            durationMs: entry.durationMs ?? null,
            status: entry.status ?? null,
            request: body(entry.request),
            response: body(entry.response),
            error: text(entry.error),
        };
    }

    /** Queues the dropped counts of the minute gone by, once it is. */
    function rollOver(now) {
        if (now - windowStart < MINUTE_MS) return;
        const at = new Date(windowStart).toISOString();
        for (const [service, count] of Object.entries(dropped)) {
            queue.push({ service, direction: "IN", operation: "dropped", at, durationMs: null, status: null,
                request: null, response: { dropped: count }, error: null });
        }
        dropped = {};
        windowStart = now;
        windowCount = 0;
    }

    function enqueue(entry) {
        const now = Date.now();
        rollOver(now);
        if (windowCount >= PER_MINUTE) {
            dropped[entry.service] = (dropped[entry.service] ?? 0) + 1;
            return;
        }
        windowCount++;
        queue.push(normalize(entry));
        if (queue.length >= BATCH_ENTRIES) {
            flush();
        } else if (!timer) {
            timer = setTimeout(flush, FLUSH_MS);
        }
    }

    /** The queued entries in batches within the entry and size bounds, each serialized. */
    function batches(entries) {
        const encoder = new TextEncoder();
        const envelope = (list) => JSON.stringify({ source: target.source, sentAt: new Date().toISOString(), entries: list });
        const result = [];
        let current = [];
        let size = 0;
        for (let entry of entries) {
            let bytes = encoder.encode(JSON.stringify(entry)).length + 1;
            if (bytes > MAX_BATCH_BYTES) {
                entry = { ...entry, request: entry.request && { truncated: true }, response: entry.response && { truncated: true } };
                bytes = encoder.encode(JSON.stringify(entry)).length + 1;
            }
            if (current.length && (current.length >= BATCH_ENTRIES || size + bytes > MAX_BATCH_BYTES)) {
                result.push(envelope(current));
                current = [];
                size = 0;
            }
            current.push(entry);
            size += bytes;
        }
        if (current.length) result.push(envelope(current));
        return result;
    }

    function post(json, leaving) {
        const { url, source } = target;
        if (leaving && source === "widget" && navigator.sendBeacon) {
            navigator.sendBeacon(url, new Blob([json], { type: "application/json" }));
            return;
        }
        const headers = { "Content-Type": "application/json" };
        // The widget page has no session, hence no CSRF token.
        if (source === "page") headers["X-XSRF-TOKEN"] = window.CatapultCsrf?.readCookie("XSRF-TOKEN") ?? "";
        const sent = target;
        fetch(url, { method: "POST", headers, body: json, keepalive: !!leaving })
            .then((answer) => {
                if (answer.status === 409 && target === sent) capture.disable();
            })
            .catch(() => {});
    }

    /** Sends what is queued; `leaving`: the page is going away. */
    function flush(leaving = false) {
        try {
            clearTimeout(timer);
            timer = null;
            if (!target) return;
            rollOver(Date.now());
            if (!queue.length) return;
            const entries = queue;
            queue = [];
            batches(entries).forEach((json) => post(json, leaving));
        } catch {
            // a lost batch, never a broken page
        }
    }

    const capture = {
        /** Starts capturing to `url` as `source` (page or widget); the same target keeps its queue. */
        enable({ url, source }) {
            if (target?.url === url && target?.source === source) return;
            reset();
            target = { url, source };
        },

        /** Stops capturing, dropping what was queued. */
        disable() {
            target = null;
            reset();
        },

        active() {
            return target !== null;
        },

        /** Queues an exchange: { service, direction, operation, at?, durationMs?, status?, request?, response?, error? }. */
        record(entry) {
            if (!target) return;
            try {
                enqueue(entry);
            } catch {
                // never breaks the caller
            }
        },

        /** Holds a request until settle(key) brings its answer; timed from now. */
        pending(key, entry) {
            if (!target || waiting.size >= MAX_PENDING) return;
            waiting.set(key, { entry: { ...entry, at: new Date().toISOString() }, started: Date.now() });
        },

        /** Queues the request held under `key` with `patch` (status, response, error), if still held. */
        settle(key, patch) {
            if (!target) return;
            const held = waiting.get(key);
            if (!held) return;
            waiting.delete(key);
            capture.record({ ...held.entry, ...patch, durationMs: Date.now() - held.started });
        },

        /** Settles every request of `service` still held, e.g. as "connection closed". */
        settleAll(service, patch) {
            if (!target) return;
            for (const [key, held] of [...waiting]) {
                if (held.entry.service === service) capture.settle(key, patch);
            }
        },

        flush() {
            flush();
        },

        stripObs,
        stripTwitchat,
    };

    window.addEventListener("pagehide", () => flush(true));

    if (document.body?.dataset.debugCapture === "true") capture.enable({ url: PAGE_URL, source: "page" });

    return capture;
})();
