/**
 * The admin Well-known tab: a file's dialog (new, a registry suffix's, or an existing file's) is
 * fetched from /spa/admin/well-known/file; its Content-Type follows the path's extension until
 * edited by hand (an existing file's never does), its URL preview follows path and location; a
 * file imported is read in the browser as text into the content (refused when larger than 64 KiB
 * or holding a NUL byte, i.e. not text). Save / Delete post to AdminWellKnownController, then the
 * tab is reloaded; a refusal is told by its status (data-error-400/404/409, else data-error with
 * its {0}, "—" when not sent) and leaves the dialog open. The registry is filtered as typed.
 */
window.CatapultAdminWellKnown = (function () {
    const MAX_BYTES = 65_536;

    function contentType(path) {
        const p = (path || "").toLowerCase();
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".xml")) return "application/xml";
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        return "text/plain; charset=utf-8";
    }

    function message(root, status = "—") {
        return root.dataset[`error-${status}`] ?? root.dataset.error.replace("{0}", status);
    }

    const value = (form, name) => form.querySelector(`[name="${name}"]`).value ?? "";

    function preview(form) {
        const prefix = value(form, "location") === "ROOT" ? "/" : "/.well-known/";
        form.querySelector(".admin-well-known-url").textContent = prefix + value(form, "path");
    }

    async function openFile(panel, root, dialog, query) {
        let response;
        try {
            response = await fetch(`/spa/admin/well-known/file${query}`);
        } catch {
            CatapultChannel.showError(message(root));
            return;
        }
        if (!response.ok) {
            CatapultChannel.showError(message(root, response.status));
            if (response.status === 404) await CatapultAdminTabs.reload(panel);
            return;
        }
        dialog.querySelector(".admin-dialog-body").innerHTML = await response.text();
        dialog.open = true;
    }

    /** Posts with `button` disabled meanwhile; true when it went through, the failure told otherwise. */
    async function send(root, button, url, body) {
        button.disabled = true;
        let status;
        try {
            const response = await CatapultCsrf.postJson(url, body);
            if (response.ok) return true;
            status = response.status;
        } catch {
            // not sent at all: no status to tell
        }
        CatapultChannel.showError(message(root, status));
        button.disabled = false;
        return false;
    }

    /** The file's text; read with a FileReader, as jsdom's File has no text(). */
    function readText(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = () => reject(reader.error);
            reader.readAsText(file);
        });
    }

    async function importFile(root, form, file) {
        if (!file) return;
        if (file.size > MAX_BYTES) {
            CatapultChannel.showError(root.dataset.errorTooLarge);
            return;
        }
        const text = await readText(file);
        if (text.includes("\u0000")) {
            CatapultChannel.showError(root.dataset.errorBinary);
            return;
        }
        form.querySelector('[name="content"]').value = text;
    }

    async function onClick(panel, target) {
        const root = panel.querySelector(".admin-well-known");
        const dialog = panel.querySelector("#admin-well-known-dialog");
        if (target.matches("#admin-well-known-new")) return openFile(panel, root, dialog, "");
        if (target.matches(".admin-well-known-configure")) {
            return openFile(panel, root, dialog, `?location=WELL_KNOWN&path=${encodeURIComponent(target.dataset.path)}`);
        }
        const row = target.closest(".admin-well-known-file");
        if (target.matches(".admin-well-known-edit")) return openFile(panel, root, dialog, `?id=${encodeURIComponent(row.dataset.id)}`);
        if (target.matches(".admin-dialog-close")) {
            dialog.open = false;
            return;
        }
        if (target.disabled) return; // its request is on its way
        if (target.matches(".admin-well-known-delete")) {
            if (!await CatapultAdminTabs.confirmed(root, target.dataset.confirm)) return;
            if (await send(root, target, `/admin/well-known/${encodeURIComponent(row.dataset.id)}/delete`, {})) {
                await CatapultAdminTabs.reload(panel);
            }
            return;
        }
        const form = target.closest(".admin-well-known-form");
        if (target.matches(".admin-well-known-import-button")) {
            form.querySelector(".admin-well-known-import").click();
            return;
        }
        if (target.matches(".admin-well-known-save")) {
            const id = form.dataset.id;
            const body = {
                location: value(form, "location"),
                path: value(form, "path").trim(),
                contentType: value(form, "contentType").trim(),
                content: value(form, "content")
            };
            if (await send(root, target, id ? `/admin/well-known/${encodeURIComponent(id)}` : "/admin/well-known", body)) {
                dialog.open = false;
                await CatapultAdminTabs.reload(panel);
            }
        }
    }

    function onInput(panel, target) {
        if (target.matches("[data-well-known-filter]")) {
            const q = (target.value ?? "").trim().toLowerCase();
            panel.querySelectorAll(".admin-iana-row").forEach(row => {
                row.hidden = q !== "" && !row.dataset.search.includes(q);
            });
            return;
        }
        const form = target.closest(".admin-well-known-form");
        if (!form) return;
        const name = target.getAttribute("name");
        if (name === "contentType") target.dataset.manual = "true";
        if (name === "path") {
            const typeField = form.querySelector('[name="contentType"]');
            if (!typeField.dataset.manual) typeField.value = contentType(target.value);
        }
        if (name === "path" || name === "location") preview(form);
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="well-known"]');
        if (!panel) return;
        panel.addEventListener("click", event => {
            const target = event.target.closest(
                "#admin-well-known-new, .admin-well-known-configure, .admin-well-known-edit, .admin-well-known-delete,"
                + " .admin-well-known-import-button, .admin-well-known-save, .admin-dialog-close");
            if (target) onClick(panel, target);
        });
        panel.addEventListener("input", event => onInput(panel, event.target));
        panel.addEventListener("change", event => {
            if (event.target.matches?.(".admin-well-known-import")) {
                const root = panel.querySelector(".admin-well-known");
                importFile(root, event.target.closest(".admin-well-known-form"), event.target.files?.[0]);
                event.target.value = "";
            } else {
                onInput(panel, event.target);
            }
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach, contentType };
})();
