/**
 * The admin Groups tab. The create card needs a key and a name; Rename (prefilled) and Add member
 * (a Twitch username) go through their dialogs; Delete confirms with the group's name first. All post
 * to AdminGroupsController with their button disabled meanwhile, and a success reloads the tab. A
 * refusal is told by its status, never by catapult-api's message: 409 (key taken), 404 (group gone,
 * or unknown login when adding a member), else unavailable. Listeners are delegated to the panel:
 * its content comes and goes.
 */
window.CatapultAdminGroups = (function () {
    const ok = status => status >= 200 && status < 300;
    const fieldOf = (scope, name) => scope.querySelector(`[name="${name}"]`);
    const valueOf = (scope, name) => (fieldOf(scope, name).value ?? "").trim();
    const urlOf = (id, action) => `/admin/groups/${encodeURIComponent(id)}/${action}`;

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

    /** What to tell for a refused status; `notFound` names what a 404 means for the action. */
    function refusal(root, status, notFound) {
        if (status === 409) return root.dataset.labelConflict;
        if (status === 404) return root.dataset[notFound];
        return status === 400 ? root.dataset.labelInvalid : root.dataset.labelUnavailable;
    }

    /** Tells the outcome of `status`: done (snackbar, reload) or refused; a vanished group also reloads. */
    async function settle(panel, root, status, notFound, doneLabel) {
        if (ok(status)) {
            window.mdui?.snackbar?.({ message: root.dataset[doneLabel] });
            await CatapultAdminTabs.reload(panel);
            return;
        }
        CatapultChannel.showError(refusal(root, status, notFound));
        if (status === 404 && notFound === "labelUnknown") await CatapultAdminTabs.reload(panel);
    }

    async function createGroup(panel, root, button) {
        const card = button.closest(".admin-groups-create");
        const key = valueOf(card, "key");
        const name = valueOf(card, "name");
        const missing = [["key", key], ["name", name]].filter(([, value]) => value === "");
        if (missing.length > 0) {
            missing.forEach(([field]) => mark(fieldOf(card, field)));
            return;
        }
        const description = valueOf(card, "description");
        const status = await post(button, "/admin/groups", { key, name, description: description || null });
        button.disabled = true; // post() re-enabled it; still busy until the tab reloads
        try {
            await settle(panel, root, status, "labelUnknown", "labelCreated");
        } finally {
            button.disabled = false;
        }
    }

    function openRename(dialog, button) {
        const form = dialog.querySelector(".admin-groups-form");
        form.dataset.id = button.dataset.rename;
        fieldOf(form, "name").value = button.dataset.name ?? "";
        fieldOf(form, "description").value = button.dataset.description ?? "";
        ["name", "description"].forEach(name => fieldOf(form, name).removeAttribute("invalid"));
        dialog.open = true;
    }

    async function saveRename(panel, root, dialog, button) {
        const form = dialog.querySelector(".admin-groups-form");
        const name = valueOf(form, "name");
        if (name === "") return mark(fieldOf(form, "name"));
        const description = valueOf(form, "description");
        const status = await post(button, urlOf(form.dataset.id, "rename"), { name, description: description || null });
        if (ok(status) || status === 404) dialog.open = false;
        await settle(panel, root, status, "labelUnknown", "labelRenamed");
    }

    function openMember(dialog, button) {
        const form = dialog.querySelector(".admin-groups-form");
        form.dataset.id = button.dataset.addMember;
        fieldOf(form, "twitchUsername").value = "";
        fieldOf(form, "twitchUsername").removeAttribute("invalid");
        dialog.open = true;
    }

    async function saveMember(panel, root, dialog, button) {
        const form = dialog.querySelector(".admin-groups-form");
        const twitchUsername = valueOf(form, "twitchUsername");
        if (twitchUsername === "") return mark(fieldOf(form, "twitchUsername"));
        const status = await post(button, urlOf(form.dataset.id, "members"), { twitchUsername });
        if (ok(status)) dialog.open = false;
        await settle(panel, root, status, "labelMemberUnknown", "labelMemberAdded");
    }

    async function remove(panel, root, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            const status = await post(button, urlOf(button.dataset.delete, "delete"), {});
            button.disabled = true; // post() re-enabled it; still busy until the tab reloads
            await settle(panel, root, status, "labelUnknown", "labelDeleted");
        } finally {
            button.disabled = false;
        }
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="groups"]');
        if (!panel) return;
        const rootOf = () => panel.querySelector(".admin-groups");
        const dialogOf = id => panel.querySelector(id);

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(
                ".admin-groups-create-save, [data-rename], [data-add-member], [data-delete],"
                + " .admin-groups-rename-save, .admin-groups-member-save, .admin-dialog-close");
            if (!target || !panel.contains(target) || target.disabled) return;
            const root = rootOf();
            if (!root) return;
            if (target.matches(".admin-groups-create-save")) createGroup(panel, root, target);
            else if (target.matches("[data-rename]")) openRename(dialogOf("#admin-groups-rename-dialog"), target);
            else if (target.matches("[data-add-member]")) openMember(dialogOf("#admin-groups-member-dialog"), target);
            else if (target.matches("[data-delete]")) remove(panel, root, target);
            else if (target.matches(".admin-groups-rename-save")) saveRename(panel, root, dialogOf("#admin-groups-rename-dialog"), target);
            else if (target.matches(".admin-groups-member-save")) saveMember(panel, root, dialogOf("#admin-groups-member-dialog"), target);
            else target.closest("mdui-dialog, [id$='-dialog']").open = false;
        });
        panel.addEventListener("input", event => event.target.removeAttribute?.("invalid"));
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
