/**
 * Keeps the page connected to the logged-in user's OBS through CatapultObs, whenever they
 * enabled OBS in their settings: /me/obs answers 204 when logged out or OBS is disabled, and
 * this then stays idle. Runs once per full page load, not on catapult:render, so the connection
 * survives SPA navigation.
 *
 * Retries with a doubling delay (1s up to 30s) while OBS is unreachable (closed, restarting),
 * but not after a refusal retrying can't fix (wrong password, unsupported rpcVersion): that
 * waits for refresh(), which channel-connections.js calls after saving the OBS settings.
 */
window.CatapultObsSession = (function () {
    const MIN_DELAY = 1000;
    const MAX_DELAY = 30000;
    const { CloseCode } = CatapultObs;
    const FINAL_CLOSE_CODES = new Set([CloseCode.AuthenticationFailed, CloseCode.UnsupportedRpcVersion]);

    let config = null;
    let delay = MIN_DELAY;
    let timer = null;
    // Bumped by every refresh(), so the retries and connects of an older config give up.
    let generation = 0;

    async function fetchConfig() {
        try {
            const response = await fetch("/me/obs", { cache: "no-store" });
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

    /** Re-reads /me/obs, then connects, reconnects or disconnects to match it. */
    async function refresh() {
        const gen = ++generation;
        const next = await fetchConfig();
        if (gen !== generation) return;
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
