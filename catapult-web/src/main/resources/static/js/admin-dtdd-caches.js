/**
 * The admin DTDD caches tab. Its summary starts the bulk refetch once confirmed, or asks it to stop, and follows it
 * while it runs: the summary is patched every few seconds (admin-tabs.js) as long as its job reads RUNNING and the
 * tab is the one shown. A cache's entries each open in full in a dialog (what the caches keep about it), or fetch
 * again on demand, then the results shown are patched (same search, same page). A refusal is told with the view's data-error-{status}, else its data-error ({0}: the status).
 * Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminDtddCaches = (function () {
    const TAB = "dtdd-caches";
    const BASE = "/admin/dtdd-caches";
    const POLL_MS = 3000;

    let timer = null;

    const panelOf = () => document.querySelector(`mdui-tab-panel[value="${TAB}"]`);

    /** Whether the summary shown reads a running refetch, in the tab shown. */
    function following(panel) {
        const job = panel.querySelector('.admin-dtdd-caches [data-admin-value="job"]');
        return job?.dataset.state === "RUNNING" && document.getElementById("admin-tabs")?.value === TAB;
    }

    /** Patches the summary every POLL_MS while it reads a running refetch; stops by itself otherwise. */
    function follow(panel) {
        clearTimeout(timer);
        timer = null;
        if (!following(panel)) return;
        timer = setTimeout(async () => {
            if (!panel.isConnected || !following(panel)) return;
            await CatapultAdminTabs.patch(panel);
            follow(panel);
        }, POLL_MS);
    }

    async function refetch(panel, root, button) {
        const query = new URLSearchParams({ key: button.dataset.key });
        const sent = await CatapultAdminTabs.post(root, button,
            `${BASE}/${encodeURIComponent(root.dataset.kind)}/refetch?${query}`, {});
        if (sent !== "ok") {
            // gone meanwhile: the list as it is now
            if (sent === 404) await CatapultAdminTabs.patch(panel);
            return;
        }
        button.disabled = false;
        window.mdui?.snackbar?.({ message: root.dataset.refetched });
        await CatapultAdminTabs.patch(panel);
    }

    /** The entry in full, into the view's dialog; a failure is told. */
    async function detail(root, button) {
        const dialog = root.querySelector("#admin-dc-dialog");
        const query = new URLSearchParams({ key: button.dataset.key });
        let response = null;
        try {
            response = await fetch(`/spa/admin/${TAB}/${encodeURIComponent(root.dataset.kind)}/entry?${query}`);
        } catch {
            // not fetched: told below
        }
        if (!response?.ok || !dialog) {
            CatapultChannel.showError(CatapultAdminTabs.refusal(root, response?.status));
            return;
        }
        dialog.querySelector(".admin-dialog-body").innerHTML = await response.text();
        dialog.open = true;
    }

    async function start(panel, root, button) {
        if (!await CatapultAdminTabs.confirmed(root, root.dataset.startConfirm, root.dataset.startDescription)) return;
        const sent = await CatapultAdminTabs.post(root, button, `${BASE}/refetch-all/start`, {});
        // one started elsewhere meanwhile: follow it all the same
        if (sent !== "ok" && sent !== 409) return;
        button.disabled = false;
        await CatapultAdminTabs.patch(panel);
        follow(panel);
    }

    async function cancel(panel, root, button) {
        if (await CatapultAdminTabs.post(root, button, `${BASE}/refetch-all/cancel`, {}) !== "ok") return;
        button.disabled = false;
        await CatapultAdminTabs.patch(panel);
    }

    function attach() {
        const panel = panelOf();
        if (!panel || panel.dataset.dtddCachesWired) return;
        panel.dataset.dtddCachesWired = "true";

        panel.addEventListener("click", event => {
            const button = event.target.closest(".admin-dc-detail, .admin-dc-refetch, #admin-dc-start, #admin-dc-cancel, .admin-dialog-close");
            if (!button || button.disabled) return;
            const root = button.closest(".admin-view");
            if (!root) return;
            if (button.matches(".admin-dialog-close")) button.closest("mdui-dialog").open = false;
            else if (button.matches(".admin-dc-detail")) detail(root, button);
            else if (button.matches(".admin-dc-refetch")) refetch(panel, root, button);
            else if (button.id === "admin-dc-start") start(panel, root, button);
            else cancel(panel, root, button);
        });
        // Back on the tab: a refetch running is followed again (left, it stopped being)
        document.getElementById("admin-tabs")?.addEventListener("change", event => {
            if (event.target.id === "admin-tabs") follow(panel);
        });
    }

    document.addEventListener("catapult:render", attach);
    // A view shown (the tab, back from a cache): a refetch running is followed again
    document.addEventListener("catapult:admin-panel", event => {
        if (event.detail?.tab === TAB) follow(event.detail.panel);
    });

    return { attach, follow };
})();
