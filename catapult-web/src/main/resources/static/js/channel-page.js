/**
 * Shared helpers for the /channel/{username} dashboard fragment scripts
 * (channel-status.js, channel-bindings.js, channel-connections.js,
 * channel-settings.js, channel-dtdd.js) and for channel-events.js, which
 * patches the DOM in place as each SSE event arrives instead of re-fetching
 * the whole fragment.
 *
 * - username()/baseUrl(): a single place deriving the channel username from the
 *   URL, instead of every file re-deriving `location.pathname.split("/")[2]`.
 *   username() is null outside /channel/{username}.
 * - refresh(): re-renders the current fragment through the SPA router, preserving
 *   the query string (page/status/source filters) instead of dropping it. Only
 *   used where no SSE event yet covers the mutation (see channel-settings.js).
 * - postJson(): wraps CatapultCsrf.postJson and surfaces a visible error when the
 *   mutation didn't succeed, instead of silently re-rendering as if it had.
 *   postAndRefresh() chains it with refresh().
 * - on(): the null-guarded addEventListener every optional control needs (owner-only
 *   and provider-dependent elements simply aren't rendered for everyone).
 * - checkedValues(): values of the checked switches under a root.
 */
window.CatapultChannel = (function () {
    function username() {
        const segments = location.pathname.split("/");
        return segments[1] === "channel" && segments[2] ? segments[2] : null;
    }

    function baseUrl() {
        return `/channel/${username()}`;
    }

    async function refresh() {
        const path = location.pathname.replace(/^\/+/, "") + location.search;
        await CatapultSpa.navigate(path, false);
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

    async function postAndRefresh(url, body) {
        await postJson(url, body);
        await refresh();
    }

    function on(id, type, handler) {
        const el = document.getElementById(id);
        el?.addEventListener(type, handler);
        return el;
    }

    // These are mdui-switch elements, not native checkboxes: the :checked pseudo-class
    // never matches them, so the checked state has to be read off the property.
    function checkedValues(root, selector) {
        return Array.from(root.querySelectorAll(selector)).filter(sw => sw.checked).map(sw => sw.value);
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
        row.remove();
        if (getBindingsElements().length === 0) {
            // #channel-bindings-empty is already server-rendered (hidden) with the right
            // translation; nothing left to build or translate by hand here.
            document.getElementById("channel-bindings-list").querySelector("mdui-collapse")?.remove();
            document.getElementById("channel-bindings-empty")?.classList.remove("hidden");
        }
    }

    // The Steam card always renders every sub-view (connected/not-connected chip,
    // has-token/no-token body, save/delete buttons, shared-token checkbox) for the
    // owner, and the Minecraft card every status sub-view (NONE/PENDING/INVITE_REJECTED/
    // ACCEPTED/REMOVED), all toggled generically by each element's own spa:on/spa:
    // switch/spa:value (see channel.html) instead of being created/destroyed per event.
    // No handler is needed here for any Steam or Minecraft event.

    return {
        username, baseUrl, refresh, postJson, postAndRefresh, showError,
        on, checkedValues,
        getBindingsElements, getBindingElement,
        setCurrentGame,
        removeBinding
    };
})();

// Keeps the active #channel-tabs tab in location.hash. refresh() re-renders the whole
// fragment without pushing a history entry, so the hash survives it and the restore
// below lands back on the tab the user was on (e.g. after saving a settings card)
// instead of resetting to the overview. Also makes each tab directly linkable.
document.addEventListener("catapult:render", function () {
    const tabs = document.getElementById("channel-tabs");
    if (!tabs) return;

    const wanted = location.hash.slice(1);
    // Non-owners only get the overview tab: ignore a hash naming one they don't have.
    if (wanted && tabs.querySelector(`mdui-tab[value="${CSS.escape(wanted)}"]`)) {
        tabs.value = wanted;
    }

    tabs.addEventListener("change", () => {
        const hash = tabs.value === "overview" ? "" : `#${tabs.value}`;
        history.replaceState(history.state, "", location.pathname + location.search + hash);
    });
});
