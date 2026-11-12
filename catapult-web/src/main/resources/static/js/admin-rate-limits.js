/**
 * The learned rate limits, in the admin Keys tab. A learned limit is corrected in the view's dialog, filled from its
 * row (an empty field leaves that part to the learning; a host has no scope to choose), or reset once confirmed; the
 * tab is then patched (admin-tabs.js). A refusal is told with the view's data-error-{status}, else its data-error
 * ({0}: the status). While the tab is shown, it is patched every few seconds (the keys' usage, the limits), unless a
 * dialog is open or the page hidden. Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminRateLimits = (function () {
    const TAB = "keys";
    const BASE = "/admin/rate-limits";
    const POLL_MS = 3000;

    let timer = null;

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
        scope.value = row.dataset.scopePinned === "true" ? row.dataset.scope : "LEARNED";
        dialog.open = true;
    }

    /** The correction the dialog holds: a limit only with its calls, a scope only for a route (LEARNED: unpinned). */
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

    /** Whether the tab is shown, its view loaded, and nothing being edited in a dialog. */
    function live(panel) {
        return panel.isConnected && document.getElementById("admin-tabs")?.value === TAB
            && document.visibilityState !== "hidden" && !!panel.querySelector(".admin-rate-limits")
            && !panel.querySelector("mdui-dialog[open]");
    }

    /** Patches the tab every POLL_MS while it is shown; stops by itself when it no longer is. */
    function follow(panel) {
        clearTimeout(timer);
        timer = null;
        if (!panel?.isConnected || document.getElementById("admin-tabs")?.value !== TAB) return;
        timer = setTimeout(async () => {
            if (live(panel)) await CatapultAdminTabs.patch(panel);
            follow(panel);
        }, POLL_MS);
    }

    function attach() {
        const panel = panelOf();
        if (!panel || panel.dataset.rateLimitsWired) return;
        panel.dataset.rateLimitsWired = "true";
        // Back on the tab: followed again (left, it stopped being)
        document.getElementById("admin-tabs")?.addEventListener("change", event => {
            if (event.target.id === "admin-tabs") follow(panel);
        });

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
    // The tab's view shown: followed
    document.addEventListener("catapult:admin-panel", event => {
        if (event.detail?.tab === TAB) follow(event.detail.panel);
    });

    return { attach, correctionOf, follow };
})();
