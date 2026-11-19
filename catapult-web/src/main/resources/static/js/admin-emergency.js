/**
 * The admin emergency tab: a provider stopped for the whole instance from the stop form, or resumed from its active
 * stop's row, each once confirmed; the tab is then patched (admin-tabs.js). A refusal is told with the view's
 * data-error-{status}, else its data-error ({0}: the status). Listeners are delegated to the panel: its content comes
 * and goes. The texts are the view's data-* attributes.
 */
window.CatapultAdminEmergency = (function () {
    const TAB = "emergency";
    const BASE = "/admin/emergency";

    const panelOf = () => document.querySelector(`mdui-tab-panel[value="${TAB}"]`);

    /** Confirms, then posts `action` for `provider`; the tab is patched once it went through. */
    async function send(panel, root, button, action, provider, confirm, description, done) {
        if (!provider) return;
        if (!await CatapultAdminTabs.confirmed(root, confirm, description)) return;
        const query = new URLSearchParams({ provider });
        if (await CatapultAdminTabs.post(root, button, `${BASE}/${action}?${query}`, {}) !== "ok") return;
        button.disabled = false;
        window.mdui?.snackbar?.({ message: done });
        await CatapultAdminTabs.patch(panel);
    }

    function attach() {
        const panel = panelOf();
        if (!panel || panel.dataset.emergencyWired) return;
        panel.dataset.emergencyWired = "true";

        panel.addEventListener("click", event => {
            const button = event.target.closest(".admin-em-lift, .admin-em-stop-button");
            if (!button || button.disabled) return;
            const root = button.closest(".admin-view");
            if (!root) return;
            if (button.matches(".admin-em-lift")) {
                send(panel, root, button, "resume", button.dataset.provider, root.dataset.liftConfirm,
                    root.dataset.liftDescription, root.dataset.lifted);
            } else {
                const provider = button.closest(".admin-em-stop").querySelector('[name="provider"]').value;
                send(panel, root, button, "stop", provider, root.dataset.stopConfirm, root.dataset.stopDescription,
                    root.dataset.stopped);
            }
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
