/**
 * The admin DTDD mapping tab. The status chips load the list of that status (the chosen one stays
 * in the tab root's data-status, so a reload after an action keeps it). Approve posts at once;
 * Reject opens a small dialog asking an optional reason first. Both post to AdminDtddMappingController
 * with their button disabled meanwhile. A refusal is told by its status, never by catapult-api's
 * message: 409 (another admin resolved it first) and 404 (gone) say so and reload the list, which no
 * longer holds the proposal. Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminDtddMapping = (function () {
    const ok = status => status >= 200 && status < 300;

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

    /** Swaps the panel's content for the list of `status`; left as is when it can't be fetched. */
    async function load(panel, status) {
        const root = panel.querySelector(".admin-dtdd-mapping");
        const unavailable = root?.dataset.labelUnavailable ?? panel.dataset.unavailable;
        let html = null;
        try {
            const response = await fetch(`/spa/admin/dtdd-mapping?status=${encodeURIComponent(status)}`);
            if (response.ok) html = await response.text();
        } catch {
            // not fetched: told below
        }
        if (html === null) {
            CatapultChannel.showError(unavailable);
            return;
        }
        panel.innerHTML = html;
    }

    /** Tells what happened to a resolution: done, already resolved, gone or unavailable; reloads unless unavailable. */
    async function settle(panel, root, status, doneLabel) {
        const reload = () => load(panel, root.dataset.status);
        if (ok(status)) {
            window.mdui?.snackbar?.({ message: root.dataset[doneLabel] });
            return reload();
        }
        if (status === 409 || status === 404) {
            CatapultChannel.showError(root.dataset[status === 409 ? "labelConflict" : "labelUnknown"]);
            return reload();
        }
        CatapultChannel.showError(root.dataset.labelUnavailable);
    }

    const urlOf = (id, action) => `/admin/dtdd-mapping/proposals/${encodeURIComponent(id)}/${action}`;

    async function approve(panel, root, button) {
        const status = await post(button, urlOf(button.dataset.approve, "approve"), {});
        button.disabled = true; // post() re-enabled it; still busy until the list reloads
        try {
            await settle(panel, root, status, "labelApproved");
        } finally {
            button.disabled = false;
        }
    }

    async function reject(panel, root, dialog, button) {
        const form = dialog.querySelector(".admin-dtdd-form");
        const reason = (form.querySelector('[name="reason"]').value ?? "").trim();
        const status = await post(button, urlOf(form.dataset.id, "reject"), reason === "" ? {} : { reason });
        if (status !== 0 && (ok(status) || status === 409 || status === 404)) dialog.open = false;
        await settle(panel, root, status, "labelRejected");
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="dtdd-mapping"]');
        if (!panel) return;
        const rootOf = () => panel.querySelector(".admin-dtdd-mapping");
        const dialogOf = () => panel.querySelector("#admin-dtdd-dialog");

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(
                "[data-status-filter], [data-approve], [data-reject], .admin-dtdd-confirm-reject, .admin-dialog-close");
            if (!target || !panel.contains(target) || target.disabled) return;
            const root = rootOf();
            if (!root) return;
            if (target.matches("[data-status-filter]")) {
                if (target.dataset.statusFilter !== root.dataset.status) load(panel, target.dataset.statusFilter);
            } else if (target.matches("[data-approve]")) {
                approve(panel, root, target);
            } else if (target.matches("[data-reject]")) {
                const dialog = dialogOf();
                const form = dialog.querySelector(".admin-dtdd-form");
                form.dataset.id = target.dataset.reject;
                form.querySelector('[name="reason"]').value = "";
                dialog.open = true;
            } else if (target.matches(".admin-dtdd-confirm-reject")) {
                reject(panel, root, dialogOf(), target);
            } else {
                dialogOf().open = false;
            }
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
