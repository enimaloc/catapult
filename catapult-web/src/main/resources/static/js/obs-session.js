/**
 * Keeps the page connected to the logged-in user's OBS through CatapultObs, whenever they
 * enabled OBS in their settings: /me/obs answers 204 when logged out or OBS is disabled, and
 * this then stays idle. Runs once per full page load, not on catapult:render, so the connection
 * survives SPA navigation.
 *
 * Retries with a doubling delay (1s up to 30s) while OBS is unreachable (closed, restarting),
 * but not after a refusal retrying can't fix (wrong password, unsupported rpcVersion): that
 * waits for refresh(), which channel-connections.js calls after saving the OBS settings.
 *
 * /me/obs also carries the Twitchat branch picked in the integration settings, handed to
 * twitchat.js ("auto" when there's none: OBS disabled, or logged out).
 *
 * A page can name another config URL in its body's data-obs-config-url (the OBS widget page,
 * /widget/obs/{token}/config, same shape); /me/obs otherwise. That page has no settings to save
 * and nobody to reload it: while it gets no config (catapult-api unreachable, 204), it asks again
 * every CONFIG_RETRY_MS, and once it has one, reads it again every CONFIG_RELOAD_MS.
 *
 * The config's debugCapture switches debug-capture.js on or off (the member's debug mode): on
 * the widget page, which is never reloaded, that periodic read is how it follows the debug mode.
 */
window.CatapultObsSession = (function () {
    const MIN_DELAY = 1000;
    const MAX_DELAY = 30000;
    const CONFIG_RETRY_MS = 30000;
    const CONFIG_RELOAD_MS = 60000;
    const PAGE_CAPTURE_URL = "/me/debug/client-exchanges";
    const { CloseCode } = CatapultObs;
    const FINAL_CLOSE_CODES = new Set([CloseCode.AuthenticationFailed, CloseCode.UnsupportedRpcVersion]);

    let config = null;
    let delay = MIN_DELAY;
    let timer = null;
    let configTimer = null;
    // Bumped by every refresh(), so the retries and connects of an older config give up.
    let generation = 0;

    const configUrl = () => document.body?.dataset.obsConfigUrl || "/me/obs";
    const isWidget = () => Boolean(document.body?.dataset.obsConfigUrl);

    async function fetchConfig() {
        try {
            const response = await fetch(configUrl(), { cache: "no-store" });
            return response.status === 200 ? await response.json() : null;
        } catch {
            return null;
        }
    }

    function retry(gen) {
        clearTimeout(timer);
        timer = setTimeout(() => attempt(gen), delay);
        delay = Math.min(delay * 2, MAX_DELAY);
    }

    async function attempt(gen) {
        if (gen !== generation || !config) return;
        try {
            await CatapultObs.connect({ host: config.obsHost, port: config.obsPort, password: config.obsPassword });
            if (gen === generation) delay = MIN_DELAY;
        } catch (error) {
            if (gen !== generation) return;
            if (FINAL_CLOSE_CODES.has(error.code)) {
                console.warn(`[obs] not retrying: ${error.message}`);
                return;
            }
            retry(gen);
        }
    }

    function sameConfig(a, b) {
        return a?.obsHost === b?.obsHost && a?.obsPort === b?.obsPort && a?.obsPassword === b?.obsPassword;
    }

    /**
     * Applies a config read: Twitchat's branch, and the debug capture when there is a config. A
     * branch Twitchat refuses leaves it as it was, and never stops the rest (connection, capture,
     * the widget's next read).
     */
    function apply(next) {
        try {
            window.CatapultTwitchat?.configure(next?.twitchatBranch ?? "auto");
        } catch (error) {
            console.warn(`[obs] Twitchat branch not applied: ${error?.message}`);
        }
        const capture = window.CatapultDebugCapture;
        if (!capture || !next) return;
        if (!next.debugCapture) {
            capture.disable();
        } else if (isWidget()) {
            capture.enable({ url: configUrl().replace(/\/config$/, "/debug/client-exchanges"), source: "widget" });
        } else {
            capture.enable({ url: PAGE_CAPTURE_URL, source: "page" });
        }
    }

    /**
     * The widget's periodic read: only a config read with another connection reconnects. No config
     * (a 204, which catapult-web also answers while catapult-api is down) or a failed read keeps
     * everything as it is, the OBS connection and the debug capture: a blip of the API must not cut
     * the widget off its OBS, which kept it through any outage before this read existed.
     */
    async function reload() {
        const gen = generation;
        const next = await fetchConfig();
        if (gen !== generation) return;
        if (next && !sameConfig(next, config)) {
            await use(++generation, next);
            return;
        }
        configTimer = setTimeout(reload, CONFIG_RELOAD_MS);
        if (next) apply(next);
    }

    /** Re-reads the config (/me/obs by default), then connects, reconnects or disconnects to match it. */
    async function refresh() {
        const gen = ++generation;
        clearTimeout(configTimer);
        const next = await fetchConfig();
        if (gen !== generation) return;
        await use(gen, next);
    }

    /** Connects, reconnects or disconnects to match the config read, `next` (null: none). */
    async function use(gen, next) {
        clearTimeout(configTimer);
        if (isWidget()) configTimer = next ? setTimeout(reload, CONFIG_RELOAD_MS) : setTimeout(refresh, CONFIG_RETRY_MS);
        apply(next);
        if (next && sameConfig(next, config) && CatapultObs.isConnected()) return;
        clearTimeout(timer);
        delay = MIN_DELAY;
        config = next;
        CatapultObs.close();
        if (config) await attempt(gen);
    }

    CatapultObs.onDisconnect = () => retry(generation);

    refresh();

    return { refresh };
})();
