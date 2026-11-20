/**
 * Records the page's OBS and Twitchat exchanges (obs.js, twitchat.js) while its member is in
 * debug mode, and sends them by batches to catapult-web, which relays them to the member's debug
 * journal. Off unless enabled: on a page rendered in debug mode (body data-debug-capture), or by
 * obs-session.js from its config's debugCapture (the widget page's own URL then). Every hook
 * checks active() first, so nothing is built outside debug mode.
 *
 * Secrets never leave the page, stripped before anything is queued:
 * - OBS data is kept by an allowlist (OBS_KEPT): only the requests and events known to carry no
 *   secret (versions, stats, scenes, scene items, the input list, stream, recording, transitions,
 *   studio mode, audio levels…) keep their values. Every other request, answer or event (input,
 *   filter and stream service settings, profile parameters, persistent data, vendor messages,
 *   custom events not from Twitchat, any type not listed) keeps only its shape: the same keys,
 *   each value replaced by its type, with its length for a string or an array (stripObs);
 * - OBS's opening messages (Hello, Identify, Identified, Reidentify) keep only their versions and
 *   event subscriptions, the authentication dropped, anything else by shape;
 * - by name, as catapult-api's DebugRedactor names them (password, token, *key, auth…), whatever
 *   the value, in what is kept;
 * - Catapult's signed action tokens, UUIDs anywhere in a Twitchat message's data (stripTwitchat);
 * - in every URL of any string (overlay URLs carry account tokens in their path or query, an
 *   ffmpeg input its passphrase): userinfo dropped, path, query values and fragment redacted.
 *   Catapult's own URLs are kept but for the widget token, removed everywhere, percent-encoded too.
 *
 * A batch goes every 5s or at 50 entries; past 300 entries a minute, entries are counted instead,
 * one "dropped" entry per service for that minute, sent once the minute is over. A Twitchat
 * answer is recorded twice, in the action it settles and as the event it is, both counting
 * toward that bound. A failed batch is dropped, never sent again; a 409 (the debug mode ended)
 * stops the capture. When the page goes away, what is left goes in batches small enough for a
 * beacon or a keepalive fetch.
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
    // What a beacon or a keepalive fetch may carry in flight (about 64 KiB), with some room.
    const MAX_LEAVING_BYTES = 60 * 1024;
    // The most requests held for their answer: past it (answers OBS never sent), new ones go uncaptured.
    const MAX_PENDING = 1000;
    const REDACTED = "<redacted>";
    // The widget token's path segment, plain or percent-encoded.
    const WIDGET_TOKEN = /(\/|%2F)widget(\/|%2F)obs(\/|%2F)(?:(?!%2F|%3F|%23)[^/?#&"'\s\\<>])+/gi;
    // A URL in any string; a <redacted> already in it is part of it.
    const URL_IN_TEXT = /\b[a-z][a-z0-9+.-]*:\/\/(?:<redacted>|[^\s"'<>()\\])+/gi;
    // Catapult's signed action tokens are UUIDs, written anywhere in a payload's strings.
    const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
    // DebugRedactor's names ("code" aside: OBS's request status), and the parts of a name that
    // make it a credential's; any name ending in "key" too (streamKey, stream_key, apiKey).
    const SECRET_NAMES = new Set(["authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key",
        "private-token", "key", "api_key", "apikey", "access_token", "refresh_token", "id_token", "client_secret",
        "token", "password", "device_code", "user_code", "openid.sig", "rpsticket", "usertokens", "identitytoken",
        "passphrase", "bearer_token"]);
    const SECRET_NAME_PARTS = ["token", "secret", "pass", "ticket", "signature", "apikey", "api_key", "api-key",
        "bearer", "auth", "cred"];
    const NOT_SECRET = new Set(["token_type", "tokentype"]);
    // The OBS requests and events whose data is kept, each with the members of its data reduced to
    // their shape anyway (settings, which may hold anything); any other type keeps only its shape.
    const NONE = [];
    const OBS_KEPT = new Map([
        // OBS itself
        ["GetVersion", NONE], ["GetStats", NONE], ["Sleep", NONE], ["ExitStarted", NONE],
        // scenes
        ["GetSceneList", NONE], ["GetCurrentProgramScene", NONE], ["SetCurrentProgramScene", NONE],
        ["GetCurrentPreviewScene", NONE], ["SetCurrentPreviewScene", NONE], ["CurrentProgramSceneChanged", NONE],
        ["CurrentPreviewSceneChanged", NONE], ["SceneListChanged", NONE], ["SceneCreated", NONE],
        ["SceneRemoved", NONE], ["SceneNameChanged", NONE],
        // scene items
        ["GetSceneItemList", NONE], ["GetSceneItemId", NONE], ["GetSceneItemEnabled", NONE],
        ["SetSceneItemEnabled", NONE], ["SceneItemCreated", NONE], ["SceneItemRemoved", NONE],
        ["SceneItemEnableStateChanged", NONE], ["SceneItemListReindexed", NONE],
        // inputs: their names and kinds, never their settings
        ["GetInputList", NONE], ["GetInputKindList", NONE], ["CreateInput", ["inputSettings"]], ["RemoveInput", NONE],
        ["InputCreated", ["inputSettings", "defaultInputSettings"]], ["InputRemoved", NONE], ["InputNameChanged", NONE],
        ["GetInputMute", NONE], ["SetInputMute", NONE], ["ToggleInputMute", NONE], ["InputMuteStateChanged", NONE],
        ["GetInputVolume", NONE], ["SetInputVolume", NONE], ["InputVolumeChanged", NONE],
        ["InputActiveStateChanged", NONE], ["InputShowStateChanged", NONE],
        // stream and recording
        ["GetStreamStatus", NONE], ["StartStream", NONE], ["StopStream", NONE], ["ToggleStream", NONE],
        ["StreamStateChanged", NONE], ["GetRecordStatus", NONE], ["StartRecord", NONE], ["StopRecord", NONE],
        ["ToggleRecord", NONE], ["PauseRecord", NONE], ["ResumeRecord", NONE], ["RecordStateChanged", NONE],
        // transitions and studio mode
        ["GetSceneTransitionList", NONE], ["GetCurrentSceneTransition", ["transitionSettings"]],
        ["SetCurrentSceneTransition", NONE], ["SetCurrentSceneTransitionDuration", NONE],
        ["TriggerStudioModeTransition", NONE], ["CurrentSceneTransitionChanged", NONE], ["SceneTransitionStarted", NONE],
        ["SceneTransitionEnded", NONE], ["GetStudioModeEnabled", NONE], ["SetStudioModeEnabled", NONE],
        ["StudioModeStateChanged", NONE],
    ]);
    // What an OBS opening message (Hello, Identify, Identified, Reidentify) keeps; its other members
    // by shape, its authentication (challenge and salt, or the proof) dropped.
    const OBS_OPENING_KEPT = new Set(["obsWebSocketVersion", "rpcVersion", "negotiatedRpcVersion", "eventSubscriptions"]);
    // What a request, an answer or an event keeps around its data; its other members by shape.
    const OBS_FRAME_KEPT = new Set(["requestType", "requestId", "requestStatus", "eventType", "eventIntent",
        "haltOnFailure", "executionType"]);
    const OBS_DATA = new Set(["requestData", "responseData", "eventData"]);
    // The items of an array of objects described one by one, up to this many.
    const SHAPE_ITEMS = 20;

    let target = null;
    // The widget token itself, once known from the widget's URL: removed as is and percent-encoded.
    let widgetTokens = [];
    let queue = [];
    let timer = null;
    let droppedTimer = null;
    const waiting = new Map();
    let windowStart = 0;
    let windowCount = 0;
    let dropped = {};

    function reset() {
        clearTimeout(timer);
        clearTimeout(droppedTimer);
        timer = null;
        droppedTimer = null;
        queue = [];
        waiting.clear();
        windowStart = 0;
        windowCount = 0;
        dropped = {};
    }

    function isSecretName(name) {
        const n = String(name).toLowerCase();
        if (NOT_SECRET.has(n)) return false;
        return SECRET_NAMES.has(n) || n.endsWith("key") || SECRET_NAME_PARTS.some((part) => n.includes(part));
    }

    const redacted = (value) => (value === null || value === undefined ? value : REDACTED);

    function safely(strip, value) {
        try {
            return strip(value);
        } catch {
            return null;
        }
    }

    /**
     * The shape of `value`, never a value: a string as string(length), an array of plain values as
     * array(length), an object with its keys, each member's shape, a number or a boolean as its type.
     */
    function shape(value) {
        if (value === null || value === undefined) return null;
        if (typeof value === "string") return `string(${value.length})`;
        if (Array.isArray(value)) {
            if (!value.some((item) => item && typeof item === "object")) return `array(${value.length})`;
            const items = value.slice(0, SHAPE_ITEMS).map(shape);
            return value.length > SHAPE_ITEMS ? [...items, `…(+${value.length - SHAPE_ITEMS})`] : items;
        }
        if (typeof value === "object") return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, shape(item)]));
        return typeof value;
    }

    /** A copy of `value`, its secret-named members redacted (`authentication` dropped), `strings` applied to its strings. */
    function scrub(value, strings = (text) => text) {
        if (Array.isArray(value)) return value.map((item) => scrub(item, strings));
        if (typeof value === "string") return strings(value);
        if (!value || typeof value !== "object") return value;
        const copy = {};
        for (const [key, item] of Object.entries(value)) {
            if (key === "authentication") continue;
            copy[strings(key)] = isSecretName(key) ? redacted(item) : scrub(item, strings);
        }
        return copy;
    }

    const noTokens = (text) => text.replace(UUID, REDACTED);

    /** A Twitchat message (or its data) without the action tokens and secret-named members of its data. */
    function stripTwitchat(message) {
        return safely((value) => {
            if (!value || typeof value !== "object" || Array.isArray(value)) return scrub(value, noTokens);
            const copy = { ...value };
            if ("data" in copy) copy.data = scrub(copy.data, noTokens);
            if (Array.isArray(copy.payloads)) copy.payloads = copy.payloads.map(stripTwitchat);
            return copy;
        }, message);
    }

    const isTwitchat = (value) => value?.origin === "twitchat";

    /** The data of an OBS request, answer or event of `type`: kept when allowed, its shape otherwise. */
    function obsData(type, data) {
        if (type === "CustomEvent" && isTwitchat(data)) return stripTwitchat(data);
        if (type === "BroadcastCustomEvent" && isTwitchat(data?.eventData)) return { ...shape(data), eventData: stripTwitchat(data.eventData) };
        const shaped = OBS_KEPT.get(type);
        if (!shaped || !data || typeof data !== "object" || Array.isArray(data)) return shaped ? scrub(data) : shape(data);
        const copy = scrub(data);
        for (const name of shaped) if (name in data) copy[name] = shape(data[name]);
        return copy;
    }

    /** An OBS message's data (op's d): see the allowlist above. */
    function obsMessage(d) {
        if (!d || typeof d !== "object" || Array.isArray(d)) return shape(d);
        const type = d.requestType ?? d.eventType;
        const framed = type !== undefined || Array.isArray(d.requests) || Array.isArray(d.results);
        const copy = {};
        for (const [key, item] of Object.entries(d)) {
            if (framed && (key === "requests" || key === "results")) {
                copy[key] = Array.isArray(item) ? item.map(obsMessage) : shape(item);
            } else if (framed && OBS_DATA.has(key)) {
                copy[key] = obsData(type, item);
            } else if (key !== "authentication") {
                copy[key] = (framed ? OBS_FRAME_KEPT : OBS_OPENING_KEPT).has(key) ? scrub(item) : shape(item);
            }
        }
        return copy;
    }

    /** An OBS message's data without its authentication or secrets, its Twitchat messages stripped. */
    function stripObs(message) {
        return safely(obsMessage, message);
    }

    /** A URL found in a string, without what could be a secret in it: Catapult's own are kept as they are. */
    function scrubUrl(found) {
        let url;
        try {
            url = new URL(found);
        } catch {
            return `${found.slice(0, found.indexOf("://"))}://${REDACTED}`;
        }
        if (/^https?:$/.test(url.protocol) && url.origin === location.origin) return found;
        let out = `${url.protocol}//${url.host}`;
        if (url.pathname && url.pathname !== "/") out += `/${REDACTED}`;
        else out += url.pathname;
        if (url.search) out += `?${[...url.searchParams.keys()].map((name) => `${encodeURIComponent(name)}=${REDACTED}`).join("&")}`;
        if (url.hash) out += `#${REDACTED}`;
        return out;
    }

    /** A string as sent: no widget token, no secret in its URLs. */
    function clean(text) {
        let result = text;
        for (const token of widgetTokens) result = result.split(token).join(REDACTED);
        return result.replace(WIDGET_TOKEN, `$1widget$2obs$3${REDACTED}`).replace(URL_IN_TEXT, scrubUrl);
    }

    /** A body as sent: a copy, cleaned, truncated past the journal's bound. */
    function body(value) {
        if (value === null || value === undefined) return null;
        let json;
        try {
            json = clean(JSON.stringify(value));
        } catch {
            return { unserializable: true };
        }
        if (json.length <= MAX_BODY_CHARS) return JSON.parse(json);
        return { truncated: `${json.slice(0, MAX_BODY_CHARS)}…(+${json.length - MAX_BODY_CHARS} chars)` };
    }

    const text = (value) => (value === null || value === undefined ? null : clean(String(value)));

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
            // Sent once the minute is over, even if nothing comes after.
            droppedTimer ??= setTimeout(() => {
                droppedTimer = null;
                flush();
            }, windowStart + MINUTE_MS - now);
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

    /**
     * The queued entries in batches within the entry and size bounds, each serialized. An entry
     * still over the bound once its bodies were truncated (multibyte or much escaped text) loses
     * its bodies altogether.
     */
    function batches(entries, maxBytes) {
        const encoder = new TextEncoder();
        const envelope = (list) => JSON.stringify({ source: target.source, sentAt: new Date().toISOString(), entries: list });
        const result = [];
        let current = [];
        let size = 0;
        for (let entry of entries) {
            let bytes = encoder.encode(JSON.stringify(entry)).length + 1;
            if (bytes > maxBytes) {
                entry = { ...entry, request: entry.request && { truncated: true }, response: entry.response && { truncated: true } };
                bytes = encoder.encode(JSON.stringify(entry)).length + 1;
            }
            if (current.length && (current.length >= BATCH_ENTRIES || size + bytes > maxBytes)) {
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
            batches(entries, leaving ? MAX_LEAVING_BYTES : MAX_BATCH_BYTES).forEach((json) => post(json, leaving));
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
            const token = /\/widget\/obs\/([^/?#]+)\//.exec(url)?.[1];
            widgetTokens = token ? [...new Set([token, encodeURIComponent(token)])] : [];
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
