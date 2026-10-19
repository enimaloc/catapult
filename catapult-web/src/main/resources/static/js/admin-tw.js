/**
 * The admin Trigger warnings tab: adding a definition (id, label, order) and the three rebuilds, each
 * confirmed with its own text first. Everything posts to AdminTwController with its button disabled
 * meanwhile (and, for an add, until the tab has reloaded), so a double click posts once. A refusal is told
 * by its status, never by catapult-api's message: 400 (invalid), 409 (id taken), 503 (trigger warnings
 * disabled), else unavailable. "Manage keywords" is a plain data-admin-open link handled by admin-tabs.js.
 * Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminTw = (function () {
    const ID = /^[a-z0-9_]{1,40}$/;
    const ok = status => status >= 200 && status < 300;
    const fieldOf = (scope, name) => scope.querySelector(`[name="${name}"]`);
    const valueOf = (scope, name) => (fieldOf(scope, name).value ?? "").trim();

    function mark(field) {
        field.setAttribute("invalid", "");
        field.reportValidity?.();
    }

    /** The status of a POST (0 when it couldn't be sent), `button` disabled meanwhile. */
    async function post(button, url, body) {
        button.disabled = true;
        try {
            return (await CatapultCsrf.postJson(url, body)).status;
        } catch {
            return 0;
        } finally {
            button.disabled = false;
        }
    }

    function refusal(root, status) {
        if (status === 409) return root.dataset.labelConflict;
        if (status === 503) return root.dataset.labelDisabled;
        return status === 400 ? root.dataset.labelInvalid : root.dataset.labelUnavailable;
    }

    async function addDefinition(panel, root, button) {
        const card = button.closest(".admin-tw-create");
        const id = valueOf(card, "id");
        const label = valueOf(card, "label");
        const sortOrder = Number(valueOf(card, "sortOrder") || "0");
        const invalid = [
            ["id", !ID.test(id)],
            ["label", label === "" || label.length > 80],
            ["sortOrder", !Number.isInteger(sortOrder)],
        ].filter(([, bad]) => bad);
        if (invalid.length > 0) {
            invalid.forEach(([name]) => mark(fieldOf(card, name)));
            return;
        }
        const status = await post(button, "/admin/tw", { id, label, sortOrder });
        button.disabled = true; // post() re-enabled it; still busy until the tab reloads
        try {
            if (ok(status)) {
                window.mdui?.snackbar?.({ message: root.dataset.labelCreated });
                await CatapultAdminTabs.reload(panel);
                return;
            }
            CatapultChannel.showError(refusal(root, status));
        } finally {
            button.disabled = false;
        }
    }

    async function rebuild(root, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            const status = await post(button, `/admin/tw/rebuild/${button.dataset.rebuild}`, {});
            if (ok(status)) window.mdui?.snackbar?.({ message: root.dataset.labelRebuildStarted });
            else CatapultChannel.showError(refusal(root, status));
        } finally {
            button.disabled = false;
        }
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="tw"]');
        if (!panel) return;

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(".admin-tw-add-save, .admin-tw-rebuild");
            if (!target || !panel.contains(target) || target.disabled) return;
            const root = panel.querySelector(".admin-tw");
            if (!root) return;
            if (target.matches(".admin-tw-add-save")) addDefinition(panel, root, target);
            else rebuild(root, target);
        });
        panel.addEventListener("input", event => event.target.removeAttribute?.("invalid"));
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
