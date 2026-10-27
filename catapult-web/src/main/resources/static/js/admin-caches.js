/**
 * The admin caches tab's entries: the detail button fetches the record behind an entry into the
 * dialog, the delete button evicts it once confirmed (AdminCachesController) and reloads the
 * results as they were. A failure is told with the view's data-error ({0}: the status, "—" when the
 * request couldn't be sent). Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminCaches = (function () {
    function fail(root, status = "—") {
        CatapultChannel.showError(root.dataset.error.replace("{0}", status));
    }

    /** `request`'s response when it went through; otherwise null, the failure told. */
    async function attempt(root, request) {
        let response;
        try {
            response = await request();
        } catch {
            fail(root);
            return null;
        }
        if (!response.ok) {
            fail(root, response.status);
            return null;
        }
        return response;
    }

    async function detail(root, dialog, name, query) {
        const response = await attempt(root, () => fetch(`/spa/admin/caches/${name}/entry?${query}`));
        if (!response) return;
        dialog.querySelector(".admin-dialog-body").innerHTML = await response.text();
        window.CatapultTime?.refresh(dialog);
        dialog.open = true;
    }

    async function evict(panel, root, button, name, query) {
        if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
        const response = await attempt(root, () => CatapultCsrf.postJson(`/admin/caches/${name}/delete?${query}`, {}));
        if (response) await CatapultAdminTabs.reloadResults(panel);
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="caches"]');
        if (!panel) return;

        panel.addEventListener("click", event => {
            const button = event.target.closest(".admin-cache-detail, .admin-cache-delete, .admin-dialog-close");
            if (!button) return;
            const root = panel.querySelector(".admin-view[data-name]");
            const dialog = panel.querySelector("#admin-cache-dialog");
            if (button.matches(".admin-dialog-close")) {
                dialog.open = false;
                return;
            }
            const name = encodeURIComponent(root.dataset.name);
            const query = new URLSearchParams({ key: button.closest("[data-key]").dataset.key });
            if (button.matches(".admin-cache-detail")) detail(root, dialog, name, query);
            else evict(panel, root, button, name, query);
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
