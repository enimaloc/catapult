/**
 * The admin rate limits tab. A learned limit is corrected in the view's dialog, filled from its row (an empty field
 * leaves that part to the learning; a host has no scope to choose), or reset once confirmed; the list is then patched
 * (admin-tabs.js). A refusal is told with the view's data-error-{status}, else its data-error ({0}: the status).
 * Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminRateLimits = (function () {
    const TAB = "rate-limits";
    const BASE = "/admin/rate-limits";

    const panelOf = () => document.querySelector(`mdui-tab-panel[value="${TAB}"]`);

    /** A number typed in a field, null when left empty. */
    function numberOf(field) {
        const value = String(field?.value ?? "").trim();
        return value === "" ? null : Number(value);
    }

    /** The dialog filled from the row's correction (what an admin set; empty: learned). */
    function edit(root, row) {
        const dialog = root.querySelector("#admin-rl-dialog");
        if (!dialog) return;
        dialog.dataset.id = row.dataset.id;
        dialog.querySelector(".admin-rl-dialog-id code").textContent = row.dataset.id;
        dialog.querySelector('[name="calls"]').value = row.dataset.calls ?? "";
        dialog.querySelector('[name="window"]').value = row.dataset.window || "60";
        dialog.querySelector('[name="resume"]').value = row.dataset.resume ?? "";
        const scope = dialog.querySelector('[name="scope"]');
        scope.hidden = row.dataset.host === "true";
        scope.value = row.dataset.scopePinned === "true" ? row.dataset.scope : "";
        dialog.open = true;
    }

    /** The correction the dialog holds: a limit only with its calls, a scope only for a route. */
    function correctionOf(dialog) {
        const calls = numberOf(dialog.querySelector('[name="calls"]'));
        const scope = dialog.querySelector('[name="scope"]');
        return {
            limitCalls: calls,
            limitWindowSeconds: calls === null ? null : numberOf(dialog.querySelector('[name="window"]')),
            resumeSeconds: numberOf(dialog.querySelector('[name="resume"]')),
            scope: scope.hidden || !scope.value ? null : scope.value
        };
    }

    async function save(panel, root, button) {
        const dialog = button.closest("mdui-dialog");
        const query = new URLSearchParams({ id: dialog.dataset.id });
        if (await CatapultAdminTabs.post(root, button, `${BASE}/correct?${query}`, correctionOf(dialog)) !== "ok") return;
        button.disabled = false;
        dialog.open = false;
        window.mdui?.snackbar?.({ message: root.dataset.saved });
        await CatapultAdminTabs.patch(panel);
    }

    async function reset(panel, root, button) {
        if (!await CatapultAdminTabs.confirmed(root, root.dataset.resetConfirm, root.dataset.resetDescription)) return;
        const query = new URLSearchParams({ id: button.closest("[data-id]").dataset.id });
        const sent = await CatapultAdminTabs.post(root, button, `${BASE}/reset?${query}`, {});
        // gone meanwhile: the list as it is now
        if (sent !== "ok" && sent !== 404) return;
        button.disabled = false;
        await CatapultAdminTabs.patch(panel);
    }

    function attach() {
        const panel = panelOf();
        if (!panel || panel.dataset.rateLimitsWired) return;
        panel.dataset.rateLimitsWired = "true";

        panel.addEventListener("click", event => {
            const button = event.target.closest(".admin-rl-edit, .admin-rl-reset, .admin-rl-save, .admin-dialog-close");
            if (!button || button.disabled) return;
            const root = button.closest(".admin-view");
            if (!root) return;
            if (button.matches(".admin-dialog-close")) button.closest("mdui-dialog").open = false;
            else if (button.matches(".admin-rl-edit")) edit(root, button.closest("[data-id]"));
            else if (button.matches(".admin-rl-save")) save(panel, root, button);
            else reset(panel, root, button);
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach, correctionOf };
})();
