/**
 * The admin page's lazy tabs (data-admin-lazy): each one's content comes from /spa/admin/{tab}
 * the first time it is shown, and its sub-view (a cache's entries, a repository's rows) from
 * /spa/admin/{tab}/{sub}, named in the hash as #tab/sub so it can be linked and the back button
 * comes back to the list (spa.js re-renders the page on popstate, and this restores the view).
 * A sub-view's search and pagination only swap its [data-admin-results], keeping the search
 * field focused. Each new content is announced with "catapult:admin-panel" for the tab scripts.
 * A panel that comes with the page (data-admin-prerendered: Members) is lazy only for its sub-views:
 * its list is already there, and comes from /spa/admin/{tab} again on the way back from one.
 *
 * After an edit, patch() brings the view up to date without rewriting it: the view is fetched again
 * and only its marked parts are reconciled with what the server rendered. A [data-admin-list] keeps
 * its [data-admin-key] children by key: a gone key's row is removed, a new key's row (as rendered by
 * the server) is inserted at its place, and a row the server renders differently than last time is
 * patched in place (its attributes and texts; only a part whose shape changed is replaced). A
 * [data-admin-value] (a count, an empty state) is patched the same way. Anything else (forms,
 * dialogs, what the admin is typing) stays as it is. A view whose marked parts don't match the new
 * one's (a list now unavailable, a member gone) is rendered again as a whole.
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

    /**
     * The HTML the server last rendered for each marked part shown (a list's row, a value): a part
     * the server renders the same is left alone, whatever the scripts did to it since.
     */
    const rendered = new WeakMap();

    function stateOf(panel) {
        let state = states.get(panel);
        if (!state) {
            const current = route();
            const sub = current.tab === panel.getAttribute("value") ? current.sub : null;
            const shown = panel.hasAttribute("data-admin-prerendered") && !sub ? fragmentUrl(tabOf(panel), null) : null;
            state = { view: shown, sub, q: "", page: 0, results: null };
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
        // a nested sub-view (a member's debug page: {id}/debug) keeps its slash, which a path can't carry encoded
        const base = `/spa/admin/${tab}` + (sub ? `/${sub.split("/").map(encodeURIComponent).join("/")}` : "");
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

    /** Tells the tab scripts about new content in `panel`: its whole view, or only the `root` patch() inserted. */
    function announce(panel, root = panel) {
        document.dispatchEvent(new CustomEvent("catapult:admin-panel", { detail: { tab: tabOf(panel), panel, root } }));
    }

    const LIST = "[data-admin-list]";
    const VALUE = "[data-admin-value]";
    const KEY = "data-admin-key";

    /** `html` parsed, not yet in the page, each of its marked parts remembered as rendered. */
    function parse(html) {
        const template = document.createElement("template");
        template.innerHTML = html;
        template.content.querySelectorAll(`[${KEY}],${VALUE}`).forEach(part => rendered.set(part, part.outerHTML));
        return template.content;
    }

    /** The marked parts of `root` that are no list's row (a row is its list's to reconcile). */
    function partsOf(root) {
        return Array.from(root.querySelectorAll(`${LIST},${VALUE}`)).filter(part => !part.parentElement?.closest(`[${KEY}]`));
    }

    const nameOf = part => part.matches(LIST) ? `list:${part.dataset.adminList}` : `value:${part.dataset.adminValue}`;

    function syncAttributes(current, fresh) {
        for (const { name } of Array.from(current.attributes)) {
            if (!fresh.hasAttribute(name)) current.removeAttribute(name);
        }
        for (const { name, value } of Array.from(fresh.attributes)) {
            if (current.getAttribute(name) !== value) current.setAttribute(name, value);
        }
    }

    const sameAttributes = (a, b) => a.attributes.length === b.attributes.length
        && Array.from(b.attributes).every(({ name, value }) => a.getAttribute(name) === value);

    /**
     * Makes `current` look like `fresh` with as few node changes as it takes: attributes and texts are
     * set in place, a node of another kind (or children of another count) is replaced by the fresh one,
     * each element inserted that way listed in `inserted`. A live time's text is time.js's: it is
     * replaced only when its time changed. Returns the node now in `current`'s place.
     */
    function morph(current, fresh, inserted) {
        const replaced = current.nodeName !== fresh.nodeName
            || (current.nodeType === Node.ELEMENT_NODE && current.matches("template") && current.innerHTML !== fresh.innerHTML)
            || (current.nodeType === Node.ELEMENT_NODE && current.matches("[data-live-time]") && !sameAttributes(current, fresh));
        if (replaced) {
            current.replaceWith(fresh);
            if (fresh.nodeType === Node.ELEMENT_NODE) inserted.push(fresh);
            return fresh;
        }
        if (current.nodeType !== Node.ELEMENT_NODE) {
            if (current.nodeValue !== fresh.nodeValue) current.nodeValue = fresh.nodeValue;
            return current;
        }
        syncAttributes(current, fresh);
        if (current.matches("[data-live-time]")) return current;
        const now = Array.from(current.childNodes);
        const next = Array.from(fresh.childNodes);
        if (now.length !== next.length) {
            current.replaceChildren(...next);
            inserted.push(...next.filter(node => node.nodeType === Node.ELEMENT_NODE));
            return current;
        }
        now.forEach((node, i) => morph(node, next[i], inserted));
        return current;
    }

    /** Patches `current` (a row or a value) into `fresh` unless the server rendered it the same as last time. */
    function patchPart(current, fresh, inserted) {
        const html = rendered.get(fresh);
        if (rendered.get(current) === html) return current;
        const now = morph(current, fresh, inserted);
        rendered.set(now, html);
        return now;
    }

    /** Reconciles `current`'s keyed rows with `fresh`'s: removed, inserted at their place, or patched. */
    function patchList(current, fresh, inserted) {
        syncAttributes(current, fresh);
        const keyed = node => node.hasAttribute(KEY);
        const rows = new Map(Array.from(current.children).filter(keyed).map(row => [row.getAttribute(KEY), row]));
        const next = Array.from(fresh.children);
        const kept = new Set(next.filter(keyed).map(row => row.getAttribute(KEY)));
        rows.forEach((row, key) => {
            if (!kept.has(key)) row.remove();
        });
        next.forEach((freshRow, i) => {
            if (!keyed(freshRow)) return; // a header or a footer: as rendered the first time
            const shown = rows.get(freshRow.getAttribute(KEY));
            let row = freshRow;
            if (shown) row = patchPart(shown, freshRow, inserted);
            else inserted.push(freshRow);
            const at = current.children[i] ?? null;
            if (at !== row) current.insertBefore(row, at);
        });
    }

    /** Puts `content` in `panel` as its whole view. */
    function renderView(panel, content) {
        panel.replaceChildren(content);
        announce(panel);
    }

    /** Renders `panel`'s view for the current hash, unless it already shows it (`force`: anyway). */
    async function show(panel, force = false) {
        const tab = tabOf(panel);
        const current = route();
        const sub = current.tab === tab ? current.sub : null;
        // a member's debug page keeps its filters in the page's query string, so that a reload keeps them
        const url = fragmentUrl(tab, sub, sub?.endsWith("/debug") ? Object.fromEntries(new URLSearchParams(location.search)) : undefined);
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
        renderView(panel, parse(html));
    }

    /**
     * Brings `panel`'s view up to date after an edit, patching only its marked parts the server now
     * renders differently (see the top). A view that can't be patched is rendered again as a whole.
     * `from`: the URL of what the view shows, when a tab script fetched it itself (a filter of its own).
     */
    async function patch(panel, from) {
        const state = stateOf(panel);
        const { view, q, page } = state;
        if (!view) return show(panel, true);
        // a sub-view searched or paged is patched from the results it shows
        const url = from ?? (q || page ? fragmentUrl(tabOf(panel), state.sub, { q, page }) : view);
        const html = await fetchHtml(url);
        // another view, search or page was asked for meanwhile
        if (state.view !== view || state.q !== q || state.page !== page) return;
        if (html === null) {
            CatapultChannel.showError(panel.dataset.unavailable);
            return;
        }
        const fresh = parse(html);
        const now = partsOf(panel);
        const next = partsOf(fresh);
        if (now.length === 0 || now.length !== next.length || now.some((part, i) => nameOf(part) !== nameOf(next[i]))) {
            renderView(panel, fresh);
            return;
        }
        const inserted = [];
        now.forEach((part, i) => {
            if (part.matches(LIST)) patchList(part, next[i], inserted);
            else patchPart(part, next[i], inserted);
        });
        inserted.forEach(root => announce(panel, root));
    }

    /** Empties `scope`'s named fields back to their rendered value (a form after what it added was saved). */
    function reset(scope) {
        scope.querySelectorAll("[name]").forEach(field => {
            field.value = field.getAttribute("value") ?? "";
            field.removeAttribute("invalid");
        });
    }

    /** Opens `sub` in `panel` (null: back to the list), as a new history entry. */
    function open(panel, sub) {
        const tab = tabOf(panel);
        const hash = sub ? `#${tab}/${encodeURIComponent(sub)}` : `#${tab}`;
        // the filters of a debug page stay on it
        const leavesDebug = stateOf(panel).sub?.endsWith("/debug") || sub?.endsWith("/debug");
        history.pushState(history.state, "", location.pathname + (leavesDebug ? "" : location.search) + hash);
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
        const fresh = html === null ? null : parse(html).querySelector("[data-admin-results]");
        const current = panel.querySelector("[data-admin-results]");
        if (!fresh || !current) {
            CatapultChannel.showError(panel.dataset.unavailable);
            return;
        }
        current.replaceWith(fresh);
        announce(panel);
    }

    /** Renders `panel`'s view again as a whole: a retry, or a change to more than its marked parts. */
    function reload(panel) {
        return show(panel, true);
    }

    /** Re-fetches the results as they are (same search, same page), after a change to them. */
    function reloadResults(panel) {
        const { q, page } = stateOf(panel);
        return results(panel, q, page);
    }

    /**
     * Resolves to whether the admin confirmed `message` (with `description` under it, if any), in `root`'s
     * data-confirm-text / data-cancel-text.
     */
    function confirmed(root, message, description) {
        return mdui.confirm({
            headline: message,
            ...(description ? { description } : {}),
            confirmText: root.dataset.confirmText,
            cancelText: root.dataset.cancelText
        }).then(() => true, () => false);
    }

    /** `root`'s data-error-{status} for a refused admin action, else its data-error with the status ("—" when not sent). */
    function refusal(root, status = "—") {
        return root.dataset[`error-${status}`] ?? root.dataset.error.replace("{0}", status);
    }

    /**
     * Posts `body` to `url` with `button` disabled meanwhile: "ok" when it went through, else the
     * refusal's status (null when it couldn't be sent), told to the admin, and the button usable again.
     */
    async function post(root, button, url, body) {
        button.disabled = true;
        let status;
        try {
            const response = await CatapultCsrf.postJson(url, body);
            if (response.ok) return "ok";
            status = response.status;
        } catch {
            // not sent at all: no status to tell
        }
        CatapultChannel.showError(refusal(root, status));
        button.disabled = false;
        return status ?? null;
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
        const panels = Array.from(tabs.querySelectorAll("mdui-tab-panel[data-admin-lazy],mdui-tab-panel[data-admin-prerendered]"));
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

    return { route, show, reload, patch, reset, reloadResults, confirmed, refusal, post };
})();
