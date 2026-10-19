/**
 * The admin data tab's rows: a row (or "New") fetches its dialog, Save posts its fields to
 * AdminDataController (create or update), Delete removes it once confirmed; then the results are
 * reloaded as they were. A refusal is told by its status (data-error-400/404/409 on the view,
 * data-error for any other, its {0} "—" when the request couldn't be sent) and leaves the dialog
 * open. Listeners are delegated to the panel.
 */
window.CatapultAdminData = (function () {
    /** `request`'s response when it went through; otherwise null, the failure told. */
    async function attempt(root, request) {
        let response;
        try {
            response = await request();
        } catch {
            CatapultChannel.showError(CatapultAdminTabs.refusal(root));
            return null;
        }
        if (!response.ok) {
            CatapultChannel.showError(CatapultAdminTabs.refusal(root, response.status));
            return null;
        }
        return response;
    }

    const idQuery = id => (id ? `?id=${encodeURIComponent(id)}` : "");

    async function openRow(root, dialog, id) {
        const response = await attempt(root, () =>
            fetch(`/spa/admin/data/${encodeURIComponent(root.dataset.repo)}/row${idQuery(id)}`));
        if (!response) return;
        dialog.querySelector(".admin-dialog-body").innerHTML = await response.text();
        dialog.open = true;
    }

    /**
     * The dialog's fields by name: a new row's blank ones stay unset, for catapult-api's defaults; an
     * existing row's only when changed from their data-initial (the value rendered; none when blank),
     * since a field left as it was may be blank for a null catapult-api can't read back from "" (a
     * number, a date).
     */
    function fields(form, creating) {
        const body = {};
        form.querySelectorAll("mdui-text-field[name]").forEach(field => {
            const value = field.value ?? "";
            if (creating ? value === "" : value === (field.dataset.initial ?? "")) return;
            body[field.getAttribute("name")] = value;
        });
        return body;
    }

    /** Posts with `button` disabled meanwhile (no second row from a double-click), again usable on failure. */
    async function send(panel, root, dialog, button, url, body) {
        button.disabled = true;
        if (!await attempt(root, () => CatapultCsrf.postJson(url, body))) {
            button.disabled = false;
            return;
        }
        dialog.open = false;
        await CatapultAdminTabs.reloadResults(panel);
    }

    async function onClick(panel, target) {
        const root = panel.querySelector(".admin-view[data-repo]");
        const dialog = panel.querySelector("#admin-data-dialog");
        const repo = encodeURIComponent(root.dataset.repo);
        if (target.matches("#admin-data-new")) return openRow(root, dialog, null);
        if (target.matches(".admin-data-row")) return openRow(root, dialog, target.dataset.id);
        if (target.matches(".admin-dialog-close")) {
            dialog.open = false;
            return;
        }
        if (target.disabled) return; // its request is on its way
        const form = target.closest(".admin-data-form");
        const id = form.dataset.id || null;
        if (target.matches(".admin-data-save")) {
            const action = id ? "update" : "create";
            return send(panel, root, dialog, target, `/admin/data/${repo}/${action}${idQuery(id)}`, fields(form, !id));
        }
        if (await CatapultAdminTabs.confirmed(root, target.dataset.confirm)) {
            return send(panel, root, dialog, target, `/admin/data/${repo}/delete${idQuery(id)}`, {});
        }
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="data"]');
        if (!panel) return;
        panel.addEventListener("click", event => {
            const target = event.target.closest(
                "#admin-data-new, .admin-data-row, .admin-data-save, .admin-data-delete, .admin-dialog-close");
            if (target) onClick(panel, target);
        });
        panel.addEventListener("keydown", event => {
            const row = event.target.closest?.(".admin-data-row");
            if (row && (event.key === "Enter" || event.key === " ")) {
                event.preventDefault();
                onClick(panel, row);
            }
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
