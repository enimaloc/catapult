/**
 * The admin Whitelist tab. The whitelist and the invitations each have a switch; a Twitch id is
 * added from the card (digits only), and an entry deleted after a confirmation. The settings card
 * saves the global cap and the default max uses (empty meaning unlimited) with "invitees can invite";
 * an invite's Quota dialog sets its max uses (empty meaning the default) and whether it can
 * reinvite (default, yes or no). Everything posts to AdminWhitelistController with the button
 * disabled meanwhile, and a success reloads the tab. A refusal is told by its status, never by
 * catapult-api's message: 400 (refused values), 404 (invite gone), else unavailable. Listeners are
 * delegated to the panel: its content comes and goes.
 */
window.CatapultAdminWhitelist = (function () {
    const ok = status => status >= 200 && status < 300;
    const fieldOf = (scope, name) => scope.querySelector(`[name="${name}"]`);
    const valueOf = (scope, name) => (fieldOf(scope, name).value ?? "").trim();
    const urlOf = (id, action) => `/admin/whitelist/${encodeURIComponent(id)}/${action}`;

    function mark(field) {
        field.setAttribute("invalid", "");
        field.reportValidity?.();
    }

    /** "" → null (unlimited / default); a whole number ≥ `min` → it; anything else → undefined (refused). */
    function numberOf(scope, name, min) {
        const text = valueOf(scope, name);
        if (text === "") return null;
        const value = Number(text);
        return Number.isInteger(value) && value >= min ? value : undefined;
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
        if (status === 404) return root.dataset.labelUnknown;
        return status === 400 ? root.dataset.labelInvalid : root.dataset.labelUnavailable;
    }

    /** Tells the outcome of `status`: done (snackbar, reload) or refused; a vanished invite also reloads. */
    async function settle(panel, root, status, doneLabel) {
        if (ok(status)) {
            window.mdui?.snackbar?.({ message: root.dataset[doneLabel] });
            await CatapultAdminTabs.reload(panel);
            return;
        }
        CatapultChannel.showError(refusal(root, status));
        if (status === 404) await CatapultAdminTabs.reload(panel);
    }

    /** Posts, then holds the button disabled until the tab has reloaded: a second click must do nothing. */
    async function act(panel, root, button, url, body, doneLabel) {
        const status = await post(button, url, body);
        button.disabled = true; // post() re-enabled it; still busy until the tab reloads
        try {
            await settle(panel, root, status, doneLabel);
        } finally {
            button.disabled = false;
        }
    }

    function addEntry(panel, root, button) {
        const card = button.closest(".admin-whitelist-add");
        const twitchId = valueOf(card, "twitchId");
        if (!/^\d{1,20}$/.test(twitchId)) return mark(fieldOf(card, "twitchId"));
        return act(panel, root, button, "/admin/whitelist/add", { twitchId }, "labelAdded");
    }

    async function remove(panel, root, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            await act(panel, root, button, urlOf(button.dataset.delete, "delete"), {}, "labelDeleted");
        } finally {
            button.disabled = false;
        }
    }

    function saveSettings(panel, root, button) {
        const card = button.closest(".admin-whitelist-settings");
        const globalMaxMembers = numberOf(card, "globalMaxMembers", 1);
        const defaultMaxUses = numberOf(card, "defaultMaxUses", 0);
        if (globalMaxMembers === undefined) mark(fieldOf(card, "globalMaxMembers"));
        if (defaultMaxUses === undefined) mark(fieldOf(card, "defaultMaxUses"));
        if (globalMaxMembers === undefined || defaultMaxUses === undefined) return;
        const defaultCanReinvite = fieldOf(card, "defaultCanReinvite").checked === true;
        return act(panel, root, button, "/admin/whitelist/invite/settings",
            { globalMaxMembers, defaultMaxUses, defaultCanReinvite }, "labelSettingsSaved");
    }

    function openQuota(dialog, button) {
        const form = dialog.querySelector(".admin-whitelist-form");
        form.dataset.id = button.dataset.quota;
        const maxUses = fieldOf(form, "maxUses");
        maxUses.value = button.dataset.maxUses ?? "";
        maxUses.removeAttribute("invalid");
        fieldOf(form, "canReinvite").value = button.dataset.canReinvite ?? "";
        dialog.open = true;
    }

    async function saveQuota(panel, root, dialog, button) {
        const form = dialog.querySelector(".admin-whitelist-form");
        const maxUses = numberOf(form, "maxUses", 0);
        if (maxUses === undefined) return mark(fieldOf(form, "maxUses"));
        const reinvite = valueOf(form, "canReinvite");
        const canReinvite = reinvite === "" ? null : reinvite === "true";
        const status = await post(button, `/admin/whitelist/invite/${encodeURIComponent(form.dataset.id)}/quota`, { maxUses, canReinvite });
        if (ok(status) || status === 404) dialog.open = false;
        await settle(panel, root, status, "labelQuotaSaved");
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="whitelist"]');
        if (!panel) return;
        const rootOf = () => panel.querySelector(".admin-whitelist");
        const dialogOf = () => panel.querySelector("#admin-whitelist-quota-dialog");

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(
                "[data-whitelist-toggle], [data-invites-toggle], .admin-whitelist-add-save, [data-delete],"
                + " .admin-whitelist-settings-save, [data-quota], .admin-whitelist-quota-save, .admin-dialog-close");
            if (!target || !panel.contains(target) || target.disabled) return;
            const root = rootOf();
            if (!root) return;
            if (target.matches("[data-whitelist-toggle]")) act(panel, root, target, "/admin/whitelist/toggle", {}, "labelToggled");
            else if (target.matches("[data-invites-toggle]")) act(panel, root, target, "/admin/whitelist/invite/toggle", {}, "labelInvitesToggled");
            else if (target.matches(".admin-whitelist-add-save")) addEntry(panel, root, target);
            else if (target.matches("[data-delete]")) remove(panel, root, target);
            else if (target.matches(".admin-whitelist-settings-save")) saveSettings(panel, root, target);
            else if (target.matches("[data-quota]")) openQuota(dialogOf(), target);
            else if (target.matches(".admin-whitelist-quota-save")) saveQuota(panel, root, dialogOf(), target);
            else target.closest("mdui-dialog, [id$='-dialog']").open = false;
        });
        panel.addEventListener("input", event => event.target.removeAttribute?.("invalid"));
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
