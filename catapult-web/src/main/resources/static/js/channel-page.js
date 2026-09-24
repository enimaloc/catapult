/**
 * Shared helpers for the /channel/{username} dashboard fragment scripts
 * (channel-status.js, channel-bindings.js, channel-connections.js,
 * channel-settings.js, channel-dtdd.js).
 *
 * - username()/baseUrl(): a single place deriving the channel username from the
 *   URL, instead of every file re-deriving `location.pathname.split("/")[2]`.
 * - refresh(): re-renders the current fragment through the SPA router, preserving
 *   the query string (page/status/source filters) instead of dropping it.
 * - postJson(): wraps CatapultCsrf.postJson and surfaces a visible error when the
 *   mutation didn't succeed, instead of silently re-rendering as if it had.
 */
window.CatapultChannel = (function () {
    function username() {
        return location.pathname.split("/")[2];
    }

    function baseUrl() {
        return `/channel/${username()}`;
    }

    async function refresh() {
        const path = location.pathname.replace(/^\/+/, "") + location.search;
        await navigate(path, false);
    }

    function showError(message) {
        const snackbar = document.createElement("mdui-snackbar");
        snackbar.textContent = message;
        snackbar.open = true;
        document.body.appendChild(snackbar);
    }

    async function postJson(url, body) {
        const response = await CatapultCsrf.postJson(url, body);
        if (!response.ok) {
            showError(`Action failed (${response.status})`);
        }
        return response;
    }

    return { username, baseUrl, refresh, postJson, showError };
})();
