/**
 * The admin Meta tab's meta tags. The form below the list adds a tag; a row's edit button loads that
 * tag into it (Save, or Cancel to go back to adding) and its delete button removes it after a
 * confirmation. Each action posts to AdminMetaTagsController, the button held disabled until it is
 * answered; a success reloads the tab, a refusal is told by its status (400, 404, 409, else generic).
 * Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminMetaTags = (function () {
    const BASE = "/admin/meta/tags";

    function message(root, status = "—") {
        return root.dataset[`error-${status}`] ?? root.dataset.error.replace("{0}", status);
    }

    /** "ok", else the refusal's status (null when it couldn't be sent), already told to the admin. */
    async function send(root, button, url, body) {
        button.disabled = true;
        let status;
        try {
            const response = await CatapultCsrf.postJson(url, body);
            if (response.ok) return "ok";
            status = response.status;
        } catch {
            // not sent at all: no status to tell
        }
        CatapultChannel.showError(message(root, status));
        button.disabled = false;
        return status ?? null;
    }

    const field = (form, name) => form.querySelector(`[name="${name}"]`);

    /** Loads `row`'s tag into the form (or empties it back to adding a new one, without a row). */
    function edit(form, row) {
        const save = form.querySelector(".admin-meta-tag-save");
        field(form, "attribute").value = row?.dataset.attribute ?? "NAME";
        field(form, "key").value = row?.dataset.key ?? "";
        field(form, "content").value = row?.dataset.content ?? "";
        if (row) form.dataset.id = row.dataset.id;
        else delete form.dataset.id;
        save.textContent = row ? form.dataset.labelSave : form.dataset.labelAdd;
        save.icon = row ? "save" : "add";
        form.querySelector(".admin-meta-tag-cancel").hidden = !row;
        if (row) field(form, "key").focus?.();
    }

    async function onClick(panel, target) {
        const root = panel.querySelector(".admin-meta-tags");
        const form = root?.querySelector(".admin-meta-tags-form");
        if (!root) return;
        if (target.matches(".admin-meta-tag-edit")) return edit(form, target.closest(".admin-meta-tag-row"));
        if (target.matches(".admin-meta-tag-cancel")) return edit(form, null);
        if (target.disabled) return; // its request is on its way
        if (target.matches(".admin-meta-tag-delete")) {
            const row = target.closest(".admin-meta-tag-row");
            if (!await CatapultAdminTabs.confirmed(root, target.dataset.confirm)) return;
            const sent = await send(root, target, `${BASE}/${encodeURIComponent(row.dataset.id)}/delete`, {});
            if (sent === "ok" || sent === 404) await CatapultAdminTabs.reload(panel);
            return;
        }
        if (target.matches(".admin-meta-tag-save")) {
            const id = form.dataset.id;
            const body = {
                attribute: field(form, "attribute").value || "NAME",
                key: (field(form, "key").value ?? "").trim(),
                content: (field(form, "content").value ?? "").trim()
            };
            const sent = await send(root, target, id ? `${BASE}/${encodeURIComponent(id)}` : BASE, body);
            if (sent === "ok" || sent === 404) await CatapultAdminTabs.reload(panel);
        }
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="meta"]');
        if (!panel) return;
        panel.addEventListener("click", event => {
            const target = event.target.closest?.(".admin-meta-tag-edit, .admin-meta-tag-delete, .admin-meta-tag-save, .admin-meta-tag-cancel");
            if (target && panel.contains(target)) onClick(panel, target);
        });
        panel.addEventListener("keydown", event => {
            if (event.key !== "Enter" || !event.target.closest?.(".admin-meta-tags-form mdui-text-field")) return;
            event.preventDefault();
            panel.querySelector(".admin-meta-tag-save")?.click();
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
