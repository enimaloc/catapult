/**
 * The Battle.net variants section of the admin game mappings tab. Its form (a dialog) creates a variant (new) or
 * changes one (from its row: the game and the key then read-only, as catapult-api refuses to change them) and shows
 * the ID the variant's games get ("fourcc:key"), the one to map. The patterns are typed one per line and sent as
 * typed: only empty lines are dropped, never a space (a trailing one is part of "Forever - "); a row's patterns are
 * read back from their spans' textContent. A deletion asks first, in its own dialog. After a change, the section's
 * list ([data-bv-list]) is swapped for a fresh one, an open dialog left as it is. A refusal is told by its status:
 * 400 invalid, 404 gone, 409 key already used, anything else unavailable. Text from data only ever reaches the page
 * through textContent. Listeners are delegated to the panel.
 */
window.CatapultAdminBattleNetVariants = (function () {
    const BASE = "/admin/battlenet-game-variants";
    const ok = status => status >= 200 && status < 300;

    /** A JSON request with the CSRF token: `{ status, body }`, status 0 when it couldn't be sent. */
    async function send(method, url, body) {
        try {
            const response = await fetch(url, {
                method,
                headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": CatapultCsrf.readCookie("XSRF-TOKEN") ?? "" },
                ...(body === undefined ? {} : { body: JSON.stringify(body) })
            });
            let json = null;
            try {
                json = await response.json();
            } catch {
                // no body (a deletion, or an error relayed without one)
            }
            return { status: response.status, body: json };
        } catch {
            return { status: 0, body: null };
        }
    }

    function sectionOf(panel) {
        return panel.querySelector(".admin-bnet-variants");
    }

    /** The label of a refused action, by its status. */
    function refusal(section, status) {
        return section.dataset[`labelError-${status}`] ?? section.dataset.labelUnavailable;
    }

    /** The patterns typed, one per line: empty lines dropped, the others kept as typed. */
    function patternsOf(text) {
        return (text ?? "").split("\n").map(line => line.endsWith("\r") ? line.slice(0, -1) : line)
            .filter(line => line.length > 0);
    }

    /** Swaps the list for a fresh one; an error is told when it couldn't be fetched. */
    async function reload(panel) {
        const section = sectionOf(panel);
        if (!section) return;
        const current = section.querySelector("[data-bv-list]");
        let fresh = null;
        try {
            const root = panel.querySelector(".admin-game-mappings");
            const page = Number(root?.dataset.page ?? 0);
            const missingPage = Number(root?.dataset.missingPage ?? 0);
            const response = await fetch(`/spa/admin/game-mappings?page=${page}&missingPage=${missingPage}`);
            if (response.ok) {
                const doc = new DOMParser().parseFromString(await response.text(), "text/html");
                fresh = doc.querySelector(".admin-bnet-variants [data-bv-list]");
            }
        } catch {
            // not fetched: told below
        }
        if (!fresh || !current) {
            CatapultChannel.showError(section.dataset.labelUnavailable);
            return;
        }
        current.replaceWith(fresh);
    }

    /** The form's fields, found once per dialog. */
    const forms = new WeakMap();

    function formOf(panel) {
        const dialog = panel.querySelector("#admin-bv-dialog");
        if (!dialog) return null;
        let form = forms.get(dialog);
        if (form) return form;
        const el = dialog.querySelector(".admin-gm-form");
        form = {
            dialog,
            title: el.querySelector(".admin-bv-form-title"),
            fourcc: el.querySelector('[name="fourcc"]'),
            key: el.querySelector('[name="variantKey"]'),
            sourceId: el.querySelector(".admin-bv-source-id"),
            matchType: el.querySelector('[name="matchType"]'),
            patterns: el.querySelector('[name="patterns"]'),
            name: el.querySelector('[name="displayName"]'),
            enabled: el.querySelector('[name="enabled"]'),
            error: el.querySelector(".admin-gm-error"),
            id: null
        };
        const showSourceId = () => {
            form.sourceId.textContent = form.fourcc.value && form.key.value ? `${form.fourcc.value}:${form.key.value}` : "";
        };
        form.fourcc.addEventListener("input", showSourceId);
        form.key.addEventListener("input", showSourceId);
        form.showSourceId = showSourceId;
        forms.set(dialog, form);
        return form;
    }

    /** Opens the form on `variant` (a row's), or empty for a new one. */
    function open(panel, variant) {
        const section = sectionOf(panel);
        const form = formOf(panel);
        form.id = variant?.id ?? null;
        form.title.textContent = form.id ? section.dataset.labelEditTitle : section.dataset.labelCreateTitle;
        form.fourcc.value = variant?.fourcc ?? "";
        form.key.value = variant?.variantKey ?? "";
        form.fourcc.readonly = form.id !== null;
        form.key.readonly = form.id !== null;
        form.matchType.value = variant?.matchType ?? "PREFIX";
        form.patterns.value = (variant?.patterns ?? []).join("\n");
        form.name.value = variant?.displayName ?? "";
        form.enabled.checked = variant?.enabled ?? false;
        form.error.hidden = true;
        form.showSourceId();
        form.dialog.open = true;
    }

    /** The variant a row shows, its patterns as typed. */
    function variantOf(row) {
        return {
            id: row.dataset.id,
            fourcc: row.dataset.fourcc,
            variantKey: row.dataset.variantKey,
            matchType: row.dataset.matchType,
            displayName: row.dataset.displayName,
            enabled: row.dataset.enabled === "true",
            patterns: [...row.querySelectorAll(".admin-bv-pattern")].map(span => span.textContent)
        };
    }

    async function save(panel, button) {
        const section = sectionOf(panel);
        const form = formOf(panel);
        const body = {
            fourcc: form.fourcc.value.trim(),
            variantKey: form.key.value.trim(),
            matchType: form.matchType.value,
            patterns: patternsOf(form.patterns.value),
            displayName: form.name.value,
            enabled: form.enabled.checked
        };
        button.disabled = true;
        const { status } = form.id
            ? await send("PUT", `${BASE}/${encodeURIComponent(form.id)}`, body)
            : await send("POST", BASE, body);
        button.disabled = false;
        if (ok(status)) {
            form.dialog.open = false;
            window.mdui?.snackbar?.({ message: section.dataset.labelSaved });
            return reload(panel);
        }
        form.error.textContent = refusal(section, status);
        form.error.hidden = false;
        if (status === 404) reload(panel);
    }

    function askDelete(panel, row) {
        const section = sectionOf(panel);
        const dialog = panel.querySelector("#admin-bv-delete-dialog");
        dialog.querySelector(".admin-bv-delete-title").textContent =
            (section.dataset.labelDeleteTitle ?? "").replace("{name}", row.dataset.displayName ?? "");
        dialog.dataset.id = row.dataset.id;
        dialog.open = true;
    }

    async function confirmDelete(panel, button) {
        const section = sectionOf(panel);
        const dialog = panel.querySelector("#admin-bv-delete-dialog");
        button.disabled = true;
        const { status } = await send("DELETE", `${BASE}/${encodeURIComponent(dialog.dataset.id)}`);
        button.disabled = false;
        if (ok(status) || status === 404) {
            dialog.open = false;
            if (ok(status)) window.mdui?.snackbar?.({ message: section.dataset.labelDeleted });
            else CatapultChannel.showError(refusal(section, status));
            return reload(panel);
        }
        CatapultChannel.showError(refusal(section, status));
    }

    const attached = new WeakSet();

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="game-mappings"]');
        if (!panel || attached.has(panel)) return;
        attached.add(panel);

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(
                "#admin-bv-new, [data-bv-edit], [data-bv-delete], .admin-bv-save, .admin-bv-confirm-delete, .admin-bv-close");
            if (!target || !panel.contains(target) || target.disabled) return;
            if (target.matches("#admin-bv-new")) {
                open(panel, null);
            } else if (target.matches("[data-bv-edit]")) {
                open(panel, variantOf(target.closest(".admin-bv-row")));
            } else if (target.matches("[data-bv-delete]")) {
                askDelete(panel, target.closest(".admin-bv-row"));
            } else if (target.matches(".admin-bv-save")) {
                save(panel, target);
            } else if (target.matches(".admin-bv-confirm-delete")) {
                confirmDelete(panel, target);
            } else {
                target.closest("mdui-dialog").open = false;
            }
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach, patternsOf };
})();
