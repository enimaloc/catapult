/**
 * The admin page's members tab. Each action posts to AdminMembersController, then re-renders the
 * page: a deletion, a promotion or an unlink changes more than its own row (the previous system
 * account, the migrate dialog's targets). The bot switch alone only posts, it already shows its state.
 *
 * A member's targeting sub-view (#members/{id}, loaded by admin-tabs.js) edits their custom flags and
 * groups: every button posts with itself disabled meanwhile and a success reloads the sub-view. A
 * refusal is told by its status (400, 404, else unavailable), never by catapult-api's message. Its
 * listeners are delegated to the panel: the sub-view comes and goes inside it.
 *
 * "Impersonate" logs the admin in as a member and opens their channel (see impersonate()).
 */
window.CatapultAdminMembers = (function () {
    function memberUrl(row, action) {
        return `/admin/members/${row.dataset.memberId}/${action}`;
    }

    // A failed post already shows its snackbar (CatapultChannel.postJson): the page stays as it was.
    async function postThenRefresh(url, body) {
        const response = await CatapultChannel.postJson(url, body);
        if (response.ok) await CatapultChannel.refresh();
        return response;
    }

    /** Resolves to whether the admin confirmed `message`. */
    function confirmed(card, message) {
        return mdui.confirm({
            headline: message,
            confirmText: card.dataset.confirmText,
            cancelText: card.dataset.cancelText
        }).then(() => true, () => false);
    }

    function openMigrate(dialog, row) {
        dialog.dataset.memberId = row.dataset.memberId;
        document.getElementById("admin-migrate-headline").textContent =
            dialog.dataset.headline.replace("{0}", row.dataset.memberName);
        const target = document.getElementById("admin-migrate-target");
        target.value = "";
        // A member can't be migrated onto themself.
        target.querySelectorAll("mdui-menu-item").forEach(item => {
            item.disabled = item.getAttribute("value") === row.dataset.memberId;
        });
        ["settings", "getters", "bindings"].forEach(part => {
            document.getElementById(`admin-migrate-${part}`).checked = false;
        });
        dialog.open = true;
    }

    async function submitMigrate(dialog) {
        const targetId = document.getElementById("admin-migrate-target").value;
        const checked = part => document.getElementById(`admin-migrate-${part}`).checked;
        const body = {
            targetId,
            migrateSettings: checked("settings"),
            migrateGetters: checked("getters"),
            migrateBindings: checked("bindings")
        };
        if (!targetId || targetId === dialog.dataset.memberId
            || !(body.migrateSettings || body.migrateGetters || body.migrateBindings)) {
            CatapultChannel.showError(dialog.dataset.incomplete);
            return;
        }
        const response = await CatapultChannel.postJson(`/admin/members/${dialog.dataset.memberId}/migrate`, body);
        if (response.ok) dialog.open = false;
    }

    // --- targeting ---------------------------------------------------------------------------

    const ok = status => status >= 200 && status < 300;
    const fieldOf = (scope, name) => scope.querySelector(`[name="${name}"]`);
    const valueOf = (scope, name) => (fieldOf(scope, name).value ?? "").trim();
    const targetingUrl = (root, action) => `/admin/members/${encodeURIComponent(root.dataset.memberId)}/${action}`;

    function mark(field) {
        field.setAttribute("invalid", "");
        field.reportValidity?.();
    }

    /** The status of a POST (0 when it couldn't be sent). */
    async function post(url, body) {
        try {
            return (await CatapultCsrf.postJson(url, body)).status;
        } catch {
            return 0;
        }
    }

    /** Tells the outcome of `status`: done (snackbar, reload) or refused; a vanished member or group also reloads. */
    async function settle(panel, root, status, doneLabel) {
        if (ok(status)) {
            window.mdui?.snackbar?.({ message: root.dataset[doneLabel] });
            await CatapultAdminTabs.reload(panel);
            return;
        }
        CatapultChannel.showError(status === 400 ? root.dataset.labelInvalid
            : status === 404 ? root.dataset.labelUnknown : root.dataset.labelUnavailable);
        if (status === 404) await CatapultAdminTabs.reload(panel);
    }

    /** Runs `action` with `button` disabled until it is done, the reload included; a second click is ignored. */
    async function busy(button, action) {
        button.disabled = true;
        try {
            await action();
        } finally {
            button.disabled = false;
        }
    }

    async function saveFlag(panel, root, button) {
        const form = button.closest(".admin-targeting-form");
        const key = valueOf(form, "key");
        if (key === "") return mark(fieldOf(form, "key"));
        const value = valueOf(form, "value");
        await busy(button, async () => settle(panel, root,
            await post(targetingUrl(root, "flags"), { key, value: value || null }), "labelFlagSaved"));
    }

    async function deleteFlag(panel, root, button) {
        await busy(button, async () => {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            // the key travels in the body: it may hold a "/" or a "%", which a path can't carry
            await settle(panel, root, await post(targetingUrl(root, "flags/delete"), { key: button.dataset.flagDelete }), "labelFlagDeleted");
        });
    }

    async function addGroup(panel, root, button) {
        const form = button.closest(".admin-targeting-form");
        const select = fieldOf(form, "groupKey");
        const groupKey = (select.value ?? "").trim();
        if (groupKey === "") return mark(select);
        await busy(button, async () => settle(panel, root,
            await post(targetingUrl(root, "groups"), { groupKey }), "labelGroupAdded"));
    }

    async function removeGroup(panel, root, button) {
        await busy(button, async () => {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            const url = targetingUrl(root, `groups/${encodeURIComponent(button.dataset.groupId)}/delete`);
            await settle(panel, root, await post(url, {}), "labelGroupRemoved");
        });
    }

    const listening = new WeakSet();

    function attachTargeting(panel) {
        if (listening.has(panel)) return;
        listening.add(panel);
        panel.addEventListener("click", event => {
            const target = event.target.closest?.(".admin-targeting-flag-save, .admin-targeting-flag-delete,"
                + " .admin-targeting-group-save, .admin-targeting-group-remove");
            const root = target?.closest(".admin-targeting");
            if (!target || !root || !panel.contains(target) || target.disabled) return;
            if (target.matches(".admin-targeting-flag-save")) saveFlag(panel, root, target);
            else if (target.matches(".admin-targeting-flag-delete")) deleteFlag(panel, root, target);
            else if (target.matches(".admin-targeting-group-save")) addGroup(panel, root, target);
            else removeGroup(panel, root, target);
        });
        panel.addEventListener("input", event => event.target.removeAttribute?.("invalid"));
        panel.addEventListener("change", event => event.target.removeAttribute?.("invalid"));
    }

    // --- impersonation -----------------------------------------------------------------------

    /**
     * Logs the admin in as the item's member (catapult-web swaps the session's JWT, keeping the
     * admin's to come back to), then opens that member's channel as a new page: every part of it
     * is built for the new account. A refusal is told on the tab, which stays as it was.
     */
    async function impersonate(card, item) {
        const login = item.dataset.impersonate;
        item.disabled = true;
        const status = await post("/admin/impersonate", { username: login });
        if (ok(status)) {
            location.assign(`/channel/${encodeURIComponent(login)}`);
            return;
        }
        item.disabled = false;
        // catapult-api's refusals (403, or 404 for a member gone meanwhile) share the old page's text
        CatapultChannel.showError(status === 403 || status === 404 ? card.dataset.labelImpersonateRefused
            : card.dataset.labelImpersonateUnavailable);
    }

    // --- the list ----------------------------------------------------------------------------

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="members"]');
        if (panel) attachTargeting(panel);
        attachList();
    }

    function attachList() {
        const card = document.getElementById("admin-members");
        if (!card) return;
        const dialog = document.getElementById("admin-migrate-dialog");
        const rowOf = el => el.closest("[data-member-id]");

        card.querySelectorAll(".admin-member-bot-toggle").forEach(sw => {
            sw.addEventListener("change", () => CatapultChannel.postSwitch(sw, memberUrl(rowOf(sw), "bot/toggle"), {}));
        });

        card.querySelectorAll("mdui-menu-item[data-action]").forEach(item => {
            item.addEventListener("click", async () => {
                if (item.disabled) return;
                const row = rowOf(item);
                if (item.dataset.action === "migrate") {
                    openMigrate(dialog, row);
                    return;
                }
                if (item.dataset.confirm && !await confirmed(card, item.dataset.confirm)) return;
                await postThenRefresh(memberUrl(row, item.dataset.action), {});
            });
        });

        card.querySelectorAll("mdui-menu-item[data-impersonate]").forEach(item => {
            item.addEventListener("click", () => {
                // disabled as rendered (the attribute) or while its request runs (the property)
                if (!item.disabled && !item.hasAttribute("disabled")) impersonate(card, item);
            });
        });

        document.getElementById("admin-migrate-cancel")?.addEventListener("click", () => { dialog.open = false; });
        document.getElementById("admin-migrate-submit")?.addEventListener("click", () => submitMigrate(dialog));
    }

    document.addEventListener("catapult:render", attach);
    // back from a member's targeting, the list is a new content
    document.addEventListener("catapult:admin-panel", event => {
        if (event.detail.tab === "members") attachList();
    });

    return { attach };
})();
