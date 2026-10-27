/**
 * The admin Links tab's social links. The form below the list adds a link; a row's edit button loads
 * that link into it (Save, or Cancel to go back to adding), its delete button removes it after a
 * confirmation and its arrows move it, sending the whole order. Each action posts to
 * AdminSocialLinksController, the button held disabled until it is answered; a success reloads the tab,
 * a refusal is told by its status (400, 404, else generic). Listeners are delegated to the panel: its
 * content comes and goes.
 */
window.CatapultAdminSocialLinks = (function () {
    const BASE = "/admin/social-links";

    const field = (form, name) => form.querySelector(`[name="${name}"]`);

    /** Loads `row`'s link into the form (or empties it back to adding a new one, without a row). */
    function edit(form, row) {
        const save = form.querySelector(".admin-social-link-save");
        field(form, "platform").value = row?.dataset.platform ?? "OTHER";
        field(form, "label").value = row?.dataset.label ?? "";
        field(form, "url").value = row?.dataset.url ?? "";
        field(form, "visible").checked = row ? row.dataset.visible === "true" : true;
        if (row) form.dataset.id = row.dataset.id;
        else delete form.dataset.id;
        save.textContent = row ? form.dataset.labelSave : form.dataset.labelAdd;
        save.icon = row ? "save" : "add";
        form.querySelector(".admin-social-link-cancel").hidden = !row;
        if (row) field(form, "url").focus?.();
    }

    /** The ids of the panel's rows, in order, with `row` swapped with its neighbour `step` away. */
    function movedIds(panel, row, step) {
        const ids = Array.from(panel.querySelectorAll(".admin-social-link-row[data-id]"), r => Number(r.dataset.id));
        const from = ids.indexOf(Number(row.dataset.id));
        const to = from + step;
        if (from < 0 || to < 0 || to >= ids.length) return null;
        [ids[from], ids[to]] = [ids[to], ids[from]];
        return ids;
    }

    async function onClick(panel, target) {
        const root = panel.querySelector(".admin-social-links");
        const form = root?.querySelector(".admin-social-links-form");
        if (!root) return;
        const row = target.closest(".admin-social-link-row");
        if (target.matches(".admin-social-link-edit")) return edit(form, row);
        if (target.matches(".admin-social-link-cancel")) return edit(form, null);
        if (target.disabled) return; // its request is on its way
        if (target.matches(".admin-social-link-up, .admin-social-link-down")) {
            const ids = movedIds(panel, row, target.matches(".admin-social-link-up") ? -1 : 1);
            if (!ids) return;
            const sent = await CatapultAdminTabs.post(root, target, `${BASE}/order`, { ids });
            // a refused order (a link added or removed meanwhile) shows the list as it is now
            if (sent === "ok" || sent === 400 || sent === 404) await CatapultAdminTabs.reload(panel);
            return;
        }
        if (target.matches(".admin-social-link-delete")) {
            target.disabled = true; // a second click meanwhile doesn't ask again
            if (!await CatapultAdminTabs.confirmed(root, target.dataset.confirm)) {
                target.disabled = false;
                return;
            }
            const sent = await CatapultAdminTabs.post(root, target, `${BASE}/${encodeURIComponent(row.dataset.id)}/delete`, {});
            if (sent === "ok" || sent === 404) await CatapultAdminTabs.reload(panel);
            return;
        }
        if (target.matches(".admin-social-link-save")) {
            const id = form.dataset.id;
            const body = {
                platform: field(form, "platform").value || "OTHER",
                label: (field(form, "label").value ?? "").trim(),
                url: (field(form, "url").value ?? "").trim(),
                visible: field(form, "visible").checked
            };
            const sent = await CatapultAdminTabs.post(root, target, id ? `${BASE}/${encodeURIComponent(id)}` : BASE, body);
            if (sent === "ok" || sent === 404) await CatapultAdminTabs.reload(panel);
        }
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="links"]');
        if (!panel) return;
        panel.addEventListener("click", event => {
            const target = event.target.closest?.(".admin-social-link-edit, .admin-social-link-delete, .admin-social-link-save, "
                + ".admin-social-link-cancel, .admin-social-link-up, .admin-social-link-down");
            if (target && panel.contains(target)) onClick(panel, target);
        });
        panel.addEventListener("keydown", event => {
            if (event.key !== "Enter" || !event.target.closest?.(".admin-social-links-form :is(mdui-text-field, input)")) return;
            if (event.isComposing || event.keyCode === 229) return; // confirming an IME's conversion
            event.preventDefault();
            panel.querySelector(".admin-social-link-save")?.click();
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
