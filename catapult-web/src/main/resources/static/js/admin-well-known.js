/**
 * The admin Well-known tab: a file's dialog (new, a registry suffix's, or an existing file's) is
 * fetched from /spa/admin/meta/file; its Content-Type follows the path's extension until
 * edited by hand (an existing file's never does), its URL preview follows path and location; a
 * file imported is read in the browser as text into the content (refused when larger than 64 KiB
 * or holding a NUL byte, i.e. not text). Save / Delete post to AdminWellKnownController, then the
 * tab is reloaded; a refusal is told by its status (data-error-400/404/409, else data-error with
 * its {0}, "—" when not sent) and leaves the dialog open. The registry is filtered as typed.
 *
 * Response tab: the status (a common one, or "other" typed in statusOther), header rows added / removed
 * (blank names not sent), Redirect = 302 + a Location row. Content tab: Form | Raw. A path with a
 * CatapultWellKnownForms descriptor (dedicated, else generated from the route's SchemaStore schema,
 * /.well-known/ only; the schema is fetched for dedicated forms too, for the save-time check) opens as a form when its raw text parses, else as raw with the reason and the Form
 * segment disabled until the raw text changes; without one, Form is hidden. A path or location changed
 * picks again (the form serialized into raw first, the schema fetched once typing pauses, the latest
 * answer winning) and never switches to the form by itself. Switching to raw writes the
 * form's serialization, and Save does too in form mode, unless no field changed since the form opened (the text then stays as written); then asks before posting content the schema flags.
 * Form state (descriptor, model, schema, mode) lives per form root in a WeakMap; group items keep their
 * `_origin` (data-origin) so apple details stay in their slots.
 */
window.CatapultAdminWellKnown = (function () {
    const MAX_BYTES = 65_536;
    const SCHEMA_DELAY_MS = 300;

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

    const states = new WeakMap(); // form root → { descriptor, model, schema, mode }
    const descriptors = new WeakMap(); // list / group fieldset → its field descriptor

    function el(tag, attrs = {}, props = {}) {
        const node = document.createElement(tag);
        Object.entries(attrs).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== false) node.setAttribute(k, v === true ? "" : v); });
        return Object.assign(node, props);
    }

    const pad = n => String(n).padStart(2, "0");
    /** An ISO instant as a datetime-local value (minutes), "" when unreadable. */
    function toLocal(iso) {
        const d = new Date(iso);
        if (!iso || Number.isNaN(d.getTime())) return "";
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    /** A datetime field's ISO value: the original text while untouched, else the local time as ISO. */
    function datetimeOf(node) {
        const local = node.value ?? "";
        if (local === node.dataset.local) return node.dataset.original;
        const d = new Date(local);
        return local === "" || Number.isNaN(d.getTime()) ? local : d.toISOString();
    }

    function removeButton(labels) {
        return el("mdui-button-icon", { icon: "close", class: "admin-well-known-item-remove", "aria-label": labels.remove });
    }

    function addButton(labels) {
        return el("mdui-button", { variant: "text", icon: "add", class: "admin-well-known-item-add" }, { textContent: labels.add });
    }

    function listRow(value, labels, help) {
        const row = el("div", { class: "admin-well-known-item" });
        row.append(el("mdui-text-field", { variant: "outlined", "data-item": true, helper: help }, { value: value ?? "" }),
            removeButton(labels));
        return row;
    }

    function groupItem(f, entry, labels) {
        const item = el("fieldset", { class: "admin-well-known-group-item", "data-origin": entry._origin });
        f.fields.forEach(sub => item.append(fieldNode(sub, entry[sub.key], labels)));
        item.append(removeButton(labels));
        return item;
    }

    function fieldNode(f, v, labels) {
        if (f.type === "list" || f.type === "group") {
            const set = el("fieldset", { "data-field": f.key, class: `admin-well-known-${f.type}` });
            descriptors.set(set, f);
            set.append(el("legend", {}, { textContent: f.label }));
            (v || []).forEach(x => set.append(f.type === "list" ? listRow(x, labels, f.help) : groupItem(f, x, labels)));
            set.append(addButton(labels));
            return set;
        }
        if (f.type === "boolean") return el("mdui-checkbox", { "data-field": f.key }, { textContent: f.label, checked: v === true });
        if (f.type === "enum") {
            const select = el("mdui-select", { "data-field": f.key, variant: "outlined", label: f.label, helper: f.help, required: f.required });
            f.options.forEach(o => select.append(el("mdui-menu-item", { value: o }, { textContent: o })));
            select.value = v ?? "";
            return select;
        }
        const node = el("mdui-text-field", {
            "data-field": f.key, variant: "outlined", label: f.label, helper: f.help, "data-help": f.help, required: f.required,
            type: f.type === "datetime" ? "datetime-local" : f.type === "number" ? "number" : undefined
        });
        if (f.type === "datetime") {
            node.dataset.original = v ?? "";
            node.dataset.local = toLocal(v);
            node.value = node.dataset.local || (v ?? "");
        } else {
            node.value = v === undefined || v === null ? "" : String(v);
        }
        return node;
    }

    function render(container, descriptor, values, labels) {
        container.replaceChildren(...descriptor.fields.map(f => fieldNode(f, values[f.key], labels)));
    }

    function readFields(scope, fields) {
        const values = {};
        for (const f of fields) {
            const node = [...scope.children].find(c => c.dataset.field === f.key); // no selector: keys are the schema's
            if (f.type === "list") {
                values[f.key] = [...node.querySelectorAll(":scope > .admin-well-known-item > [data-item]")].map(i => i.value ?? "");
            } else if (f.type === "group") {
                values[f.key] = [...node.querySelectorAll(":scope > .admin-well-known-group-item")].map(item => {
                    const entry = readFields(item, f.fields);
                    if (item.dataset.origin !== undefined) entry._origin = Number(item.dataset.origin);
                    return entry;
                });
            } else if (f.type === "boolean") {
                values[f.key] = node.checked === true;
            } else {
                values[f.key] = f.type === "datetime" ? datetimeOf(node) : node.value ?? "";
            }
        }
        return values;
    }

    const read = (container, descriptor) => readFields(container, descriptor.fields);

    function headerRow(form, name = "", headerValue = "") {
        const { labelHeaderName, labelHeaderValue, labelHeaderRemove } = form.dataset;
        const row = el("div", { class: "admin-well-known-header" });
        row.append(
            el("mdui-text-field", { "data-header": "name", variant: "outlined", label: labelHeaderName }, { value: name }),
            el("mdui-text-field", { "data-header": "value", variant: "outlined", label: labelHeaderValue }, { value: headerValue }),
            el("mdui-button-icon", { icon: "close", class: "admin-well-known-header-remove", "aria-label": labelHeaderRemove }));
        form.querySelector(".admin-well-known-headers").append(row);
        return row;
    }

    const labelsOf = form => ({ add: form.dataset.labelAdd, remove: form.dataset.labelRemove });
    const modeGroup = form => form.querySelector(".admin-well-known-mode");
    const formSegment = form => modeGroup(form).querySelector('[value="form"]');
    const reasonOf = form => form.querySelector(".admin-well-known-mode-reason");

    function showMode(form, mode) {
        states.get(form).mode = mode;
        modeGroup(form).value = mode;
        form.querySelector(".admin-well-known-fields").hidden = mode !== "form";
        form.querySelector(".admin-well-known-raw").hidden = mode !== "raw";
    }

    /** Parses the raw text into the form; on failure stays raw, Form disabled with the reason. */
    function toForm(form) {
        const state = states.get(form);
        let model;
        try {
            model = CatapultWellKnownForms.parse(state.descriptor, value(form, "content"));
        } catch (e) {
            formSegment(form).disabled = true;
            reasonOf(form).textContent = (form.dataset.labelInvalidJson ?? "{0}").replace("{0}", e.message);
            showMode(form, "raw");
            return;
        }
        state.model = model;
        const fields = form.querySelector(".admin-well-known-fields");
        render(fields, state.descriptor, model.values, labelsOf(form));
        state.pristine = JSON.stringify(read(fields, state.descriptor));
        showMode(form, "form");
        checkExpires(form);
    }

    /** The form's current text: its serialization in form mode, else the raw field. */
    function contentOf(form) {
        const state = states.get(form);
        if (state?.mode !== "form") return value(form, "content");
        const values = read(form.querySelector(".admin-well-known-fields"), state.descriptor);
        if (JSON.stringify(values) === state.pristine) return value(form, "content"); // untouched: the text stays as written
        return CatapultWellKnownForms.serialize(state.descriptor, { ...state.model, values });
    }

    function toRaw(form) {
        const state = states.get(form);
        if (state?.mode !== "form") return;
        form.querySelector('[name="content"]').value = contentOf(form);
        showMode(form, "raw");
    }

    function rawEdited(form) {
        if (!states.get(form)?.descriptor) return;
        formSegment(form).disabled = false;
        reasonOf(form).textContent = "";
    }

    function checkExpires(form) {
        const node = form.querySelector('.admin-well-known-fields [data-field="Expires"]');
        if (!node || states.get(form).descriptor.id !== "security.txt") return;
        const far = CatapultWellKnownForms.expiresFar({ values: { Expires: datetimeOf(node) } });
        const help = far ? form.dataset.labelExpiresFar : node.dataset.help;
        if (help) node.setAttribute("helper", help); else node.removeAttribute("helper");
    }

    async function schemaOf(path) {
        try {
            const response = await fetch(`/spa/admin/meta/schema?path=${encodeURIComponent(path)}`);
            return response.ok ? await response.json() : null;
        } catch {
            return null;
        }
    }

    /**
     * Picks the descriptor for the form's location and path, when they changed. On opening the schema is
     * fetched at once and the form shown when the text parses; afterwards the editor is put in raw first
     * (the form serialized into it), the schema fetched once typing pauses, and only a later answer counts.
     */
    async function pick(form, opening) {
        const state = states.get(form);
        const location = value(form, "location");
        const path = value(form, "path").trim();
        if (state.key === `${location}|${path}`) return;
        state.key = `${location}|${path}`;
        const token = ++state.token;
        clearTimeout(state.timer);
        toRaw(form);
        Object.assign(state, { descriptor: CatapultWellKnownForms.forPath(location, path), model: null, schema: null });
        formSegment(form).disabled = false;
        reasonOf(form).textContent = "";
        formSegment(form).hidden = !state.descriptor;
        if (location === "WELL_KNOWN" && path) {
            if (!opening) await new Promise(resolve => { state.timer = setTimeout(resolve, SCHEMA_DELAY_MS); });
            const current = () => state.token === token && form.isConnected; // not typed over, nor replaced
            if (!current()) return;
            const schema = await schemaOf(path);
            if (!current()) return;
            state.schema = schema;
            if (!state.descriptor && schema) state.descriptor = CatapultWellKnownForms.fromSchema(schema); // a dedicated one still renders
            formSegment(form).hidden = !state.descriptor;
        }
        if (state.descriptor && opening) toForm(form);
    }

    function setupForm(form) {
        states.set(form, { descriptor: null, model: null, schema: null, mode: "raw", key: null, token: 0, timer: null });
        showMode(form, "raw");
        return pick(form, true);
    }

    /** The status to save: the common one chosen, or the integer 200–599 typed in statusOther; else null. */
    function statusOf(form) {
        const chosen = value(form, "status");
        const typed = String(chosen === "other" ? value(form, "statusOther") : chosen).trim();
        const status = /^\d+$/.test(typed) ? Number(typed) : NaN;
        return status >= 200 && status <= 599 ? status : null;
    }

    function headersOf(form) {
        return [...form.querySelectorAll(".admin-well-known-header")].map(row => ({
            name: (row.querySelector('[data-header="name"]').value ?? "").trim(),
            value: (row.querySelector('[data-header="value"]').value ?? "").trim()
        })).filter(h => h.name !== "");
    }

    function showStatusOther(form) {
        form.querySelector('[name="statusOther"]').hidden = value(form, "status") !== "other";
    }

    function redirect(form) {
        form.querySelector('[name="status"]').value = "302";
        showStatusOther(form);
        const row = [...form.querySelectorAll(".admin-well-known-header")]
            .find(r => (r.querySelector('[data-header="name"]').value ?? "").trim().toLowerCase() === "location")
            ?? headerRow(form, "Location");
        form.querySelector(".admin-well-known-sections").value = "response";
        row.querySelector('[data-header="value"]').focus();
    }

    /** Confirms content the held schema flags; true when there's nothing to flag or the admin agreed. */
    function schemaAccepted(root, form, content) {
        const schema = states.get(form)?.schema;
        if (!schema) return true;
        const problems = CatapultWellKnownForms.checkSchema(schema, content);
        if (!problems.length) return true;
        const more = problems.length > 5 ? `, ${(form.dataset.labelSchemaMore ?? "+{0}").replace("{0}", problems.length - 5)}` : "";
        return CatapultAdminTabs.confirmed(root, form.dataset.labelSchemaWarnings.replace("{0}", problems.slice(0, 5).join(", ") + more));
    }

    function preview(form) {
        const prefix = value(form, "location") === "ROOT" ? "/" : "/.well-known/";
        form.querySelector(".admin-well-known-url").textContent = prefix + value(form, "path");
    }

    async function openFile(panel, root, dialog, query) {
        let response;
        try {
            response = await fetch(`/spa/admin/meta/file${query}`);
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
        const form = dialog.querySelector(".admin-well-known-form");
        if (form) await setupForm(form);
    }

    /** Posts with `button` disabled meanwhile; "ok" when it went through, else the status (null when not sent), told. */
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
        let text;
        try {
            text = await readText(file);
        } catch {
            CatapultChannel.showError(message(root));
            return;
        }
        if (text.includes("\u0000")) {
            CatapultChannel.showError(root.dataset.errorBinary);
            return;
        }
        toRaw(form);
        form.querySelector('[name="content"]').value = text;
        rawEdited(form);
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
            const sent = await send(root, target, `/admin/meta/files/${encodeURIComponent(row.dataset.id)}/delete`, {});
            if (sent === "ok" || sent === 404) await CatapultAdminTabs.reload(panel);
            return;
        }
        const form = target.closest(".admin-well-known-form");
        if (target.matches(".admin-well-known-import-button")) {
            form.querySelector(".admin-well-known-import").click();
            return;
        }
        if (target.matches(".admin-well-known-header-add")) return void headerRow(form);
        if (target.matches(".admin-well-known-header-remove")) return void target.closest(".admin-well-known-header").remove();
        if (target.matches(".admin-well-known-redirect")) return redirect(form);
        if (target.matches(".admin-well-known-item-remove")) {
            return void target.closest(".admin-well-known-item, .admin-well-known-group-item").remove();
        }
        if (target.matches(".admin-well-known-item-add")) {
            const set = target.parentElement;
            const f = descriptors.get(set);
            set.insertBefore(f.type === "group" ? groupItem(f, {}, labelsOf(form)) : listRow("", labelsOf(form), f.help), target);
            return;
        }
        if (target.matches(".admin-well-known-save")) {
            const id = form.dataset.id;
            const content = contentOf(form); // computed, never written back: the raw field and the snapshot stay in step
            const status = statusOf(form);
            if (status === null) return CatapultChannel.showError(message(root, 400));
            target.disabled = true; // one confirm, one post
            if (!await schemaAccepted(root, form, content)) {
                target.disabled = false;
                return;
            }
            const body = {
                location: value(form, "location"),
                path: value(form, "path").trim(),
                status,
                headers: headersOf(form),
                contentType: value(form, "contentType").trim(),
                content
            };
            const sent = await send(root, target, id ? `/admin/meta/files/${encodeURIComponent(id)}` : "/admin/meta/files", body);
            if (sent === "ok") dialog.open = false;
            if (sent === "ok" || sent === 404) await CatapultAdminTabs.reload(panel);
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
        if (target.matches('.admin-well-known-fields [data-field="Expires"]')) return checkExpires(form);
        const name = target.getAttribute("name");
        if (name === "content") rawEdited(form);
        if (name === "status") showStatusOther(form);
        if (name === "contentType") target.dataset.manual = "true";
        if (name === "path") {
            const typeField = form.querySelector('[name="contentType"]');
            if (!typeField.dataset.manual) typeField.value = contentType(target.value);
        }
        if (name === "path" || name === "location") {
            preview(form);
            if (states.has(form)) pick(form, false);
        }
    }

    /** Follows the Form | Raw group; deselecting (single selects allow it) keeps the current mode. */
    function switchMode(form) {
        const state = states.get(form);
        const wanted = modeGroup(form).value;
        if (!state || wanted === state.mode) return;
        if (wanted === "raw") toRaw(form);
        else if (wanted === "form" && state.descriptor && !formSegment(form).disabled) toForm(form);
        else modeGroup(form).value = state.mode;
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="meta"]');
        if (!panel) return;
        panel.addEventListener("click", event => {
            const target = event.target.closest(
                "#admin-well-known-new, .admin-well-known-configure, .admin-well-known-edit, .admin-well-known-delete,"
                + " .admin-well-known-import-button, .admin-well-known-save, .admin-dialog-close,"
                + " .admin-well-known-header-add, .admin-well-known-header-remove, .admin-well-known-redirect,"
                + " .admin-well-known-item-add, .admin-well-known-item-remove");
            if (target) onClick(panel, target);
        });
        panel.addEventListener("input", event => onInput(panel, event.target));
        panel.addEventListener("change", event => {
            if (event.target.matches?.(".admin-well-known-import")) {
                const root = panel.querySelector(".admin-well-known");
                importFile(root, event.target.closest(".admin-well-known-form"), event.target.files?.[0]);
                event.target.value = "";
            } else if (event.target.matches?.(".admin-well-known-mode")) {
                switchMode(event.target.closest(".admin-well-known-form"));
            } else {
                onInput(panel, event.target);
            }
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach, contentType };
})();
