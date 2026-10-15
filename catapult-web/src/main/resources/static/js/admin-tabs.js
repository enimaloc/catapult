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

    /** "#caches/igdb-game-cache" → { tab: "caches", sub: "igdb-game-cache" }. */
    function route() {
        const [tab, ...rest] = location.hash.slice(1).split("/");
        return { tab, sub: rest.length ? decodeURIComponent(rest.join("/")) : null };
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
        if (!force && panel.dataset.adminView === url) return;
        panel.dataset.adminView = url;
        panel.dataset.adminSub = sub ?? "";
        panel.dataset.adminQ = "";
        panel.dataset.adminPage = "0";
        const html = await fetchHtml(url);
        if (panel.dataset.adminView !== url) return; // another view was asked for meanwhile
        if (html === null) {
            delete panel.dataset.adminView; // shown again, it tries again
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
        panel.dataset.adminQ = q;
        panel.dataset.adminPage = String(page);
        const url = fragmentUrl(tabOf(panel), panel.dataset.adminSub || null, { q, page });
        panel.dataset.adminResults = url;
        const html = await fetchHtml(url);
        if (html === null || panel.dataset.adminResults !== url) return;
        const fresh = new DOMParser().parseFromString(html, "text/html").querySelector("[data-admin-results]");
        const current = panel.querySelector("[data-admin-results]");
        if (!fresh || !current) return;
        current.replaceWith(fresh);
        announce(panel);
    }

    function reload(panel) {
        return show(panel, true);
    }

    /** Re-fetches the results as they are (same search, same page), after a change to them. */
    function reloadResults(panel) {
        return results(panel, panel.dataset.adminQ ?? "", Number(panel.dataset.adminPage ?? 0));
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
        let searchTimer;
        panel.addEventListener("click", event => {
            const target = event.target.closest("[data-admin-open],[data-admin-back],[data-admin-page],[data-admin-retry]");
            if (!target || target.disabled) return;
            if (target.hasAttribute("data-admin-open")) open(panel, target.dataset.adminOpen);
            else if (target.hasAttribute("data-admin-back")) open(panel, null);
            else if (target.hasAttribute("data-admin-retry")) reload(panel);
            else results(panel, panel.dataset.adminQ ?? "", Number(target.dataset.adminPage));
        });
        panel.addEventListener("input", event => {
            if (!event.target.matches("[data-admin-search]")) return;
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => results(panel, event.target.value, 0), SEARCH_DELAY_MS);
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
        tabs.addEventListener("change", showActive);
        showActive();
    }

    document.addEventListener("catapult:render", attach);

    return { route, show, reload, reloadResults, confirmed };
})();
