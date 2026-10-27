/**
 * The admin CCL tab. A card's descriptors come selected from its data-saved (mdui-select only
 * takes a multiple value from JS), its Save is enabled while the selection differs from them,
 * and posts it to AdminCclController. Refresh asks catapult-api to reload the CCLs from Twitch,
 * then reloads the tab. A failure is told with the view's data-error ({0}: the status, "—" when the
 * request couldn't be sent). Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminCcl = (function () {
    const saved = card => (card.dataset.saved ? card.dataset.saved.split(",") : []);
    const selected = select => (Array.isArray(select.value) ? select.value : []);
    const same = (a, b) => a.length === b.length && a.every(value => b.includes(value));

    function selectSaved(panel) {
        panel.querySelectorAll("[data-ccl-id]").forEach(card => {
            card.querySelector(".admin-ccl-select").value = saved(card);
        });
    }

    /** Posts `body` to `url`: whether it went through, a failure told in the view's language. */
    async function post(panel, url, body) {
        let status = "—";
        try {
            const response = await CatapultCsrf.postJson(url, body);
            if (response.ok) return true;
            status = response.status;
        } catch {
            // not sent at all: no status to tell
        }
        CatapultChannel.showError(panel.querySelector("[data-error]").dataset.error.replace("{0}", status));
        return false;
    }

    async function save(panel, card, button) {
        const ids = selected(card.querySelector(".admin-ccl-select"));
        const url = `/admin/ccl/${encodeURIComponent(card.dataset.cclId)}/mappings`;
        if (!await post(panel, url, { igdbCategoryIds: ids.map(Number) })) return;
        card.dataset.saved = ids.join(",");
        button.disabled = true;
    }

    async function refresh(panel, button) {
        button.disabled = true;
        button.loading = true;
        if (await post(panel, "/admin/ccl/refresh", {})) {
            await CatapultAdminTabs.reload(panel);
        } else {
            button.disabled = false;
            button.loading = false;
        }
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="ccl"]');
        if (!panel) return;

        panel.addEventListener("change", event => {
            if (!event.target.matches(".admin-ccl-select")) return;
            const card = event.target.closest("[data-ccl-id]");
            card.querySelector(".admin-ccl-save").disabled = same(selected(event.target), saved(card));
        });

        panel.addEventListener("click", event => {
            const button = event.target.closest(".admin-ccl-save, #admin-ccl-refresh");
            if (!button || button.disabled) return;
            if (button.id === "admin-ccl-refresh") refresh(panel, button);
            else save(panel, button.closest("[data-ccl-id]"), button);
        });
    }

    document.addEventListener("catapult:render", attach);
    document.addEventListener("catapult:admin-panel", event => {
        if (event.detail.tab === "ccl") selectSaved(event.detail.panel);
    });

    return { attach };
})();
