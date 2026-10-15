/**
 * The admin caches tab's entries: the detail button fetches the record behind an entry into the
 * dialog, the delete button evicts it once confirmed (AdminCachesController) and reloads the
 * results as they were. Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminCaches = (function () {
    async function detail(root, dialog, name, query) {
        const response = await fetch(`/spa/admin/caches/${name}/entry?${query}`);
        if (!response.ok) {
            CatapultChannel.showError(root.dataset.error.replace("{0}", response.status));
            return;
        }
        dialog.querySelector(".admin-dialog-body").innerHTML = await response.text();
        dialog.open = true;
    }

    async function evict(panel, root, button, name, query) {
        if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
        const response = await CatapultChannel.postJson(`/admin/caches/${name}/delete?${query}`, {});
        if (response.ok) await CatapultAdminTabs.reloadResults(panel);
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
