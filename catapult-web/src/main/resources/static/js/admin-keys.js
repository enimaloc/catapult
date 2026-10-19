/**
 * The admin Keys tab's Steam and DTDD cards: adding a key (the field is cleared once it is accepted),
 * Refresh, and deleting a listed key after a confirmation. Everything posts to AdminKeysController
 * (provider on the card's data-provider; the key and key id in the body) with the button disabled until
 * the tab has reloaded, so a double click posts once. A refusal is told by its status, never by
 * catapult-api's message: 400 (the card's own invalid-format text), 404 (key gone: reloads), else
 * unavailable. Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminKeys = (function () {
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

    function refusal(root, card, status) {
        if (status === 404) return root.dataset.labelUnknown;
        return status === 400 ? card.dataset.labelInvalid : root.dataset.labelUnavailable;
    }

    /** Posts, then holds the button disabled until the tab has reloaded: a second click must do nothing. */
    async function act(panel, root, card, button, action, body, doneLabel, onDone) {
        const status = await post(button, `/admin/keys/${card.dataset.provider}/${action}`, body);
        button.disabled = true; // post() re-enabled it; still busy until the tab reloads
        try {
            if (ok(status)) {
                onDone?.();
                window.mdui?.snackbar?.({ message: root.dataset[doneLabel] });
                await CatapultAdminTabs.reload(panel);
                return;
            }
            CatapultChannel.showError(refusal(root, card, status));
            if (status === 404) await CatapultAdminTabs.reload(panel);
        } finally {
            button.disabled = false;
        }
    }

    function addKey(panel, root, card, button) {
        const field = card.querySelector('[name="apiKey"]');
        const apiKey = (field.value ?? "").trim();
        if (apiKey === "") {
            field.setAttribute("invalid", "");
            field.reportValidity?.();
            return;
        }
        return act(panel, root, card, button, "add", { apiKey }, "labelAdded", () => { field.value = ""; });
    }

    async function remove(panel, root, card, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            await act(panel, root, card, button, "delete", { keyId: button.dataset.keyId }, "labelDeleted");
        } finally {
            button.disabled = false;
        }
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="keys"]');
        if (!panel) return;

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(".admin-keys-add-save, .admin-keys-refresh, .admin-keys-delete");
            if (!target || !panel.contains(target) || target.disabled) return;
            const root = panel.querySelector(".admin-keys");
            const card = target.closest(".admin-keys-card");
            if (!root || !card) return;
            if (target.matches(".admin-keys-add-save")) addKey(panel, root, card, target);
            else if (target.matches(".admin-keys-refresh")) act(panel, root, card, target, "refresh", {}, "labelRefreshed");
            else remove(panel, root, card, target);
        });
        panel.addEventListener("input", event => event.target.removeAttribute?.("invalid"));
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
