/**
 * The admin Keys tab's Steam and DTDD cards: adding a key (the field is cleared once it is accepted),
 * Refresh, and deleting a listed key after a confirmation. Everything posts to AdminKeysController
 * (provider on the card's data-provider; the key and key id in the body) with the button disabled until
 * the list is patched (admin-tabs.js: the key's row inserted, updated or removed), so a double click
 * posts once. A refusal is told by its status, never by catapult-api's message: 400 (the card's own
 * invalid-format text), 404 (key gone: patched away), else unavailable. Listeners are delegated to the
 * panel: its content comes and goes.
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

    /** Posts, then holds the button disabled until the list is patched: a second click must do nothing. */
    async function act(panel, root, card, button, action, body, doneLabel, onDone) {
        const status = await post(button, `/admin/keys/${card.dataset.provider}/${action}`, body);
        button.disabled = true; // post() re-enabled it; still busy until the list is patched
        try {
            if (ok(status)) {
                onDone?.();
                window.mdui?.snackbar?.({ message: root.dataset[doneLabel] });
                await CatapultAdminTabs.patch(panel);
                return;
            }
            CatapultChannel.showError(refusal(root, card, status));
            if (status === 404) await CatapultAdminTabs.patch(panel);
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

    /**
     * The Bungie.net card: Save posts the client id, secret and key (a blank secret or key keeps the one set; the
     * fields are cleared once accepted), Remove asks first. 400 is the card's own invalid text; then the tab is
     * patched to show what is set.
     */
    async function bungie(panel, root, card, button) {
        const remove = button.matches(".admin-bungie-delete");
        button.disabled = true;
        try {
            if (remove && !await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            const field = name => card.querySelector(`[name="${name}"]`);
            const clientId = (field("clientId").value ?? "").trim();
            if (!remove && clientId === "") {
                field("clientId").setAttribute("invalid", "");
                field("clientId").reportValidity?.();
                return;
            }
            const body = remove ? {} : {
                clientId,
                clientSecret: (field("clientSecret").value ?? "").trim(),
                apiKey: (field("apiKey").value ?? "").trim(),
            };
            const status = await post(button, `/admin/bungie-credentials/${remove ? "delete" : "save"}`, body);
            button.disabled = true;
            if (!ok(status)) {
                CatapultChannel.showError(status === 400 ? card.dataset.labelInvalid : root.dataset.labelUnavailable);
                return;
            }
            if (!remove) {
                field("clientSecret").value = "";
                field("apiKey").value = "";
            }
            window.mdui?.snackbar?.({ message: remove ? card.dataset.labelRemoved : card.dataset.labelSaved });
            await CatapultAdminTabs.patch(panel);
        } finally {
            button.disabled = false;
        }
    }

    /**
     * The osu! card: Save posts the client id and secret (a blank secret keeps the one set; the field is cleared once
     * accepted), Remove asks first. 400 is the card's own invalid text; then the tab is patched to show what is set.
     */
    async function osu(panel, root, card, button) {
        const remove = button.matches(".admin-osu-delete");
        button.disabled = true;
        try {
            if (remove && !await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            const field = name => card.querySelector(`[name="${name}"]`);
            const clientId = (field("clientId").value ?? "").trim();
            if (!remove && clientId === "") {
                field("clientId").setAttribute("invalid", "");
                field("clientId").reportValidity?.();
                return;
            }
            const body = remove ? {} : { clientId, clientSecret: (field("clientSecret").value ?? "").trim() };
            const status = await post(button, `/admin/osu-credentials/${remove ? "delete" : "save"}`, body);
            button.disabled = true;
            if (!ok(status)) {
                CatapultChannel.showError(status === 400 ? card.dataset.labelInvalid : root.dataset.labelUnavailable);
                return;
            }
            if (!remove) field("clientSecret").value = "";
            window.mdui?.snackbar?.({ message: remove ? card.dataset.labelRemoved : card.dataset.labelSaved });
            await CatapultAdminTabs.patch(panel);
        } finally {
            button.disabled = false;
        }
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="keys"]');
        if (!panel) return;

        panel.addEventListener("click", event => {
            const osuButton = event.target.closest?.(".admin-osu-save, .admin-osu-delete");
            if (osuButton && panel.contains(osuButton) && !osuButton.disabled) {
                const root = panel.querySelector(".admin-keys");
                const card = osuButton.closest(".admin-osu");
                if (root && card) osu(panel, root, card, osuButton);
                return;
            }
            const bungieButton = event.target.closest?.(".admin-bungie-save, .admin-bungie-delete");
            if (bungieButton && panel.contains(bungieButton) && !bungieButton.disabled) {
                const root = panel.querySelector(".admin-keys");
                const card = bungieButton.closest(".admin-bungie");
                if (root && card) bungie(panel, root, card, bungieButton);
                return;
            }
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

    return { attach, bungie, osu };
})();
