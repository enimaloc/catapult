/**
 * A debugged member's exchanges page (#members/{id}/debug, the members tab's sub-view admin-tabs.js loads). Its
 * filters (service, direction, outcome, period) and its page go to the query string: the request for the list's
 * fragment and the address bar, which admin-tabs.js hands back to the fragment on a reload. The period's fields are
 * in the browser's local time, the query string in ISO instants (the fragment gives the instants back as
 * data-iso). A filter or a page swaps only [data-debug-results]. A row opens the exchange (a JSON read of it) in
 * the page's dialog, bodies and headers indented when they are JSON, a section it has nothing for hidden. Every
 * text from the exchange reaches the page through textContent. The trace id is shown to be copied: no Sentry
 * organisation or project is configured to build a link from. The recording is deleted once confirmed, then the
 * page goes back to the list. Listeners are delegated to the page's root, which comes and goes with the sub-view.
 */
window.CatapultAdminMemberDebug = (function () {
    const FILTERS = ["service", "direction", "outcome", "from", "to"];
    const INSTANT_FILTERS = new Set(["from", "to"]);
    const listening = new WeakSet();
    let latest = 0; // the latest list asked: an older answer is dropped

    const pad = n => String(n).padStart(2, "0");

    /** The value of a datetime-local field showing `iso` in the browser's local time. */
    function toLocalInput(iso) {
        const date = new Date(iso);
        if (Number.isNaN(date.getTime())) return "";
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
    }

    /** The filters set, as the query string they travel in (blank ones left out, the period as ISO instants). */
    function filterQuery(root) {
        const params = new URLSearchParams();
        for (const name of FILTERS) {
            const field = root.querySelector(`[data-debug-filter="${name}"]`);
            const value = (field?.value ?? "").trim();
            if (value === "") continue;
            if (INSTANT_FILTERS.has(name)) {
                const date = new Date(value);
                if (!Number.isNaN(date.getTime())) params.set(name, date.toISOString());
            } else {
                params.set(name, value);
            }
        }
        return params;
    }

    const baseUrl = root => `/spa/admin/members/${encodeURIComponent(root.dataset.debugMember)}/debug`;

    async function fetchOrNull(url) {
        try {
            return await fetch(url);
        } catch {
            return null;
        }
    }

    /** Swaps the results for those of the filters set and `page`; the address bar follows. */
    async function load(root, page) {
        const params = filterQuery(root);
        if (page > 0) params.set("page", String(page));
        const query = params.toString();
        const asked = ++latest;
        const response = await fetchOrNull(baseUrl(root) + (query ? `?${query}` : ""));
        if (asked !== latest) return;
        const fresh = response?.ok
            ? new DOMParser().parseFromString(await response.text(), "text/html").querySelector("[data-debug-results]")
            : null;
        const current = root.querySelector("[data-debug-results]");
        if (!fresh || !current) {
            CatapultChannel.showError(CatapultAdminTabs.refusal(root, response?.status));
            return;
        }
        current.replaceWith(fresh);
        history.replaceState(history.state, "", location.pathname + (query ? `?${query}` : "") + location.hash);
    }

    /** `text` indented when it is a JSON object or array, as it is otherwise; null when there is nothing to show. */
    function pretty(text) {
        if (text === null || text === undefined || text === "") return null;
        try {
            const parsed = JSON.parse(text);
            if (parsed !== null && typeof parsed === "object") return JSON.stringify(parsed, null, 2);
        } catch {
            // not JSON: as recorded
        }
        return text;
    }

    function show(dialog, name, text) {
        const field = dialog.querySelector(`[data-debug-field="${name}"]`);
        const section = dialog.querySelector(`[data-debug-section="${name}"]`);
        if (field) field.textContent = text ?? "";
        if (section) section.hidden = text === null || text === undefined || text === "";
    }

    function render(dialog, e) {
        dialog.querySelector('[data-debug-field="title"]').textContent = `${e.method ?? ""} ${e.url ?? ""}`.trim();
        dialog.querySelector('[data-debug-field="summary"]').textContent =
            [e.direction, e.service, e.operation, e.status ?? "—", `${e.durationMs} ms`].filter(part => part !== null && part !== undefined && part !== "").join(" · ");
        show(dialog, "requestHeaders", pretty(e.requestHeaders));
        show(dialog, "requestBody", pretty(e.requestBody));
        show(dialog, "responseHeaders", pretty(e.responseHeaders));
        show(dialog, "responseBody", pretty(e.responseBody));
        show(dialog, "errorMessage", e.errorMessage);
        show(dialog, "traceId", e.traceId);
        dialog.open = true;
    }

    async function openExchange(root, id) {
        const response = await fetchOrNull(`${baseUrl(root)}/${encodeURIComponent(id)}`);
        if (!response?.ok) {
            CatapultChannel.showError(response?.status === 404 ? root.dataset.labelGone : CatapultAdminTabs.refusal(root, response?.status));
            return;
        }
        render(document.getElementById("admin-debug-detail"), await response.json());
    }

    async function clear(root, button) {
        if (!await CatapultAdminTabs.confirmed(root, root.dataset.clearConfirm)) return;
        const done = await CatapultAdminTabs.post(root, button, `/admin/members/${encodeURIComponent(root.dataset.debugMember)}/debug/clear`, {});
        if (done !== "ok") return;
        button.disabled = false;
        root.querySelector("[data-admin-back]")?.click();
    }

    function copy(field) {
        navigator.clipboard?.writeText(field.textContent).then(
            () => window.mdui?.snackbar?.({ message: field.closest("[data-copied]")?.dataset.copied ?? "" }),
            () => {});
    }

    function attach() {
        const root = document.querySelector(".admin-debug");
        if (!root || listening.has(root)) return;
        listening.add(root);
        FILTERS.filter(name => INSTANT_FILTERS.has(name)).forEach(name => {
            const field = root.querySelector(`[data-debug-filter="${name}"]`);
            if (field?.dataset.iso) field.value = toLocalInput(field.dataset.iso);
        });
        root.addEventListener("change", event => {
            if (event.target.closest?.("[data-debug-filter]")) load(root, 0);
        });
        root.addEventListener("click", event => {
            const target = event.target.closest?.("[data-debug-page],[data-debug-exchange],[data-debug-clear],[data-debug-copy],[data-debug-close]");
            if (!target || !root.contains(target) || target.disabled) return;
            if (target.hasAttribute("data-debug-page")) load(root, Number(target.dataset.debugPage));
            else if (target.hasAttribute("data-debug-exchange")) openExchange(root, target.dataset.debugExchange);
            else if (target.hasAttribute("data-debug-clear")) clear(root, target);
            else if (target.hasAttribute("data-debug-close")) target.closest("mdui-dialog").open = false;
            else copy(target);
        });
    }

    document.addEventListener("catapult:admin-panel", event => {
        if (event.detail.tab === "members") attach();
    });

    return { attach };
})();
