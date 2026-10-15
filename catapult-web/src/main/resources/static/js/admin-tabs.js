/**
 * The admin page's lazy tabs (data-admin-lazy): each one's content comes from /spa/admin/{tab}
 * the first time it is shown, and its sub-view (a cache's entries, a repository's rows) from
 * /spa/admin/{tab}/{sub}, named in the hash as #tab/sub so it can be linked and the back button
 * comes back to the list (spa.js re-renders the page on popstate, and this restores the view).
 * A sub-view's search and pagination only swap its [data-admin-results], keeping the search
 * field focused. Each new content is announced with "catapult:admin-panel" for the tab scripts.
 */
window.CatapultAdminTabs = (function () {
    const SEARCH_DELAY_MS = 300;
    /** Each panel's pending search (its debounce timer), dropped when the panel shows another view. */
    const searchTimers = new WeakMap();

    /**
     * Each panel's private view state (view url, sub-view, search, page, pending results url). Kept out
     * of the panel's dataset: data-admin-page / data-admin-results there would match the fragment
     * contract's selectors, the panel itself included. A panel never shown here yet takes its
     * sub-view from the hash.
     */
    const states = new WeakMap();

    function stateOf(panel) {
        let state = states.get(panel);
        if (!state) {
            const current = route();
            state = { view: null, sub: current.tab === panel.getAttribute("value") ? current.sub : null, q: "", page: 0, results: null };
            states.set(panel, state);
        }
        return state;
    }

    /** "#caches/igdb-game-cache" → { tab: "caches", sub: "igdb-game-cache" }. */
    function route() {
        const [tab, ...rest] = location.hash.slice(1).split("/");
        return { tab, sub: rest.length ? decode(rest.join("/")) : null };
    }

    /** A malformed escape in a hand-edited hash is kept as typed rather than breaking the page. */
    function decode(text) {
        try {
            return decodeURIComponent(text);
        } catch {
            return text;
        }
    }

    function tabOf(panel) {
        return panel.getAttribute("value");
    }

    function fragmentUrl(tab, sub, params) {
        const base = `/spa/admin/${tab}` + (sub ? `/${encodeURIComponent(sub)}` : "");
        const query = params ? new URLSearchParams(params).toString() : "";
        return query ? `${base}?${query}` : base;
    }

    /** The fragment's HTML, error pages included; null when it couldn't be fetched at all. */
    async function fetchHtml(url) {
        try {
            const response = await fetch(url);
            return await response.text();
        } catch {
            return null;
        }
    }

    function announce(panel) {
        document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: tabOf(panel), panel } }));
    }

    /** Renders `panel`'s view for the current hash, unless it already shows it (`force`: anyway). */
    async function show(panel, force = false) {
        const tab = tabOf(panel);
        const current = route();
        const sub = current.tab === tab ? current.sub : null;
        const url = fragmentUrl(tab, sub);
        const state = stateOf(panel);
        if (!force && state.view === url) return;
        clearTimeout(searchTimers.get(panel)); // a search typed in the previous view stays there
        state.results = null; // and so do the results still on their way
        state.view = url;
        state.sub = sub;
        state.q = "";
        state.page = 0;
        const html = await fetchHtml(url);
        if (state.view !== url) return; // another view was asked for meanwhile
        if (html === null) {
            state.view = null; // shown again, it tries again
            CatapultChannel.showError(panel.dataset.unavailable);
            return;
        }
        panel.innerHTML = html;
        announce(panel);
    }

    /** Opens `sub` in `panel` (null: back to the list), as a new history entry. */
    function open(panel, sub) {
        const tab = tabOf(panel);
        const hash = sub ? `#${tab}/${encodeURIComponent(sub)}` : `#${tab}`;
        history.pushState(history.state, "", location.pathname + location.search + hash);
        return show(panel);
    }

    /** Swaps the sub-view's results for those of `q` and `page`; an answer older than the last ask is dropped. */
    async function results(panel, q, page) {
        const state = stateOf(panel);
        state.q = q;
        state.page = page;
        const url = fragmentUrl(tabOf(panel), state.sub, { q, page });
        state.results = url;
        const html = await fetchHtml(url);
        if (state.results !== url) return;
        const fresh = html === null ? null : new DOMParser().parseFromString(html, "text/html").querySelector("[data-admin-results]");
        const current = panel.querySelector("[data-admin-results]");
        if (!fresh || !current) {
            CatapultChannel.showError(panel.dataset.unavailable);
            return;
        }
        current.replaceWith(fresh);
        announce(panel);
    }

    function reload(panel) {
        return show(panel, true);
    }

    /** Re-fetches the results as they are (same search, same page), after a change to them. */
    function reloadResults(panel) {
        const { q, page } = stateOf(panel);
        return results(panel, q, page);
    }

    /** Resolves to whether the admin confirmed `message`, in `root`'s data-confirm-text / data-cancel-text. */
    function confirmed(root, message) {
        return mdui.confirm({
            headline: message,
            confirmText: root.dataset.confirmText,
            cancelText: root.dataset.cancelText
        }).then(() => true, () => false);
    }

    function listen(panel) {
        panel.addEventListener("click", event => {
            const target = event.target.closest("[data-admin-open],[data-admin-back],[data-admin-page],[data-admin-retry]");
            if (!target || target === panel || !panel.contains(target) || target.disabled) return;
            if (target.hasAttribute("data-admin-open")) open(panel, target.dataset.adminOpen);
            else if (target.hasAttribute("data-admin-back")) open(panel, null);
            else if (target.hasAttribute("data-admin-retry")) reload(panel);
            else results(panel, stateOf(panel).q, Number(target.dataset.adminPage));
        });
        panel.addEventListener("input", event => {
            if (!event.target.matches("[data-admin-search]")) return;
            clearTimeout(searchTimers.get(panel));
            searchTimers.set(panel, setTimeout(() => results(panel, event.target.value, 0), SEARCH_DELAY_MS));
        });
    }

    function attach() {
        const tabs = document.getElementById("admin-tabs");
        if (!tabs) return;
        const panels = Array.from(tabs.querySelectorAll("mdui-tab-panel[data-admin-lazy]"));
        panels.forEach(listen);
        const showActive = () => {
            const panel = panels.find(p => tabOf(p) === tabs.value);
            if (panel) show(panel);
        };
        // mdui's own change events bubble (composed): a field or select changing inside a panel
        // is no tab switch.
        tabs.addEventListener("change", event => {
            if (event.target === tabs) showActive();
        });
        showActive();
    }

    document.addEventListener("catapult:render", attach);

    return { route, show, reload, reloadResults, confirmed };
})();
