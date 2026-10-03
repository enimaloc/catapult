/**
 * Shared helpers for the /channel/{username} dashboard fragment scripts
 * (channel-status.js, channel-bindings.js, channel-connections.js,
 * channel-settings.js, channel-dtdd.js) and for channel-events.js, which
 * patches the DOM in place as each SSE event arrives instead of re-fetching
 * the whole fragment.
 *
 * - username()/baseUrl(): a single place deriving the channel username from the
 *   URL, instead of every file re-deriving `location.pathname.split("/")[2]`.
 * - refresh(): re-renders the current fragment through the SPA router, preserving
 *   the query string (page/status/source filters) instead of dropping it. Only
 *   used where no SSE event yet covers the mutation (see channel-settings.js).
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

    // GameChangedEvent's visibility (hasGame) and text (#channel-current-game's
    // textContent) are both driven generically now by spa:on/spa:value (see
    // channel.html). Only the accordion's own selected-item binding is left to do by
    // hand, since mdui-collapse's `value` isn't a plain element property Visibility
    // could target with a stable selector (it's the list's first, unlabelled child).
    function setCurrentGame(bindingId) {
        const bindingList = document.getElementById("channel-bindings-list").children[0];
        if (bindingList) bindingList.value = bindingId;
    }

    function getBindingsElements() {
        return Array.from(document.getElementById("channel-bindings-list").querySelectorAll("[data-binding-id]"));
    }

    function getBindingElement(id) {
        return getBindingsElements().filter(el => el.dataset.bindingId === id).at(0);
    }

    function removeBinding(id) {
        const row = getBindingElement(id);
        if (!row) return;
        const list = document.getElementById("channel-bindings-list");
        row.remove();
        if (getBindingsElements().length === 0) {
            list.querySelector("mdui-collapse")?.remove();
            const empty = document.createElement("mdui-list-item");
            empty.textContent = list.dataset.emptyText;
            list.appendChild(empty);
        }
    }

    // The Steam card always renders every sub-view (connected/not-connected chip,
    // has-token/no-token body, save/delete buttons, shared-token checkbox) for the
    // owner, and the Minecraft card every status sub-view (NONE/PENDING/INVITE_REJECTED/
    // ACCEPTED/REMOVED), all toggled generically by each element's own spa:on/spa:
    // switch/spa:value (see channel.html) instead of being created/destroyed per event.
    // No handler is needed here for any Steam or Minecraft event.

    return {
        username, baseUrl, refresh, postJson, showError,
        getBindingsElements, getBindingElement,
        setCurrentGame,
        removeBinding
    };
})();
