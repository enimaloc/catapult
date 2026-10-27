/**
 * The admin Trigger warnings tab: adding a definition (id, label, order) and the three rebuilds, each
 * confirmed with its own text first. Everything posts to AdminTwController with its button disabled
 * meanwhile (and, for an add, until the tab has reloaded), so a double click posts once. A refusal is told
 * by its status, never by catapult-api's message: 400 (invalid), 409 (id taken), 503 (trigger warnings
 * disabled), else unavailable. "Manage keywords" is a plain data-admin-open link handled by admin-tabs.js.
 *
 * The keywords sub-view (#tw/{id}) adds and removes (confirmed) Steam keywords of 2 to 50 characters, the
 * keyword travelling in the JSON body, and tests the saved keywords plus the one being typed on a Steam app:
 * the answer is built with textContent only (the notes are data). Refusals are told by status as above.
 * Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminTw = (function () {
    const ID = /^[a-z0-9_]{1,40}$/;
    const ok = status => status >= 200 && status < 300;
    const fieldOf = (scope, name) => scope.querySelector(`[name="${name}"]`);
    const valueOf = (scope, name) => (fieldOf(scope, name).value ?? "").trim();

    function mark(field) {
        field.setAttribute("invalid", "");
        field.reportValidity?.();
    }

    /** The response of a POST (null when it couldn't be sent), `button` disabled meanwhile. */
    async function send(button, url, body) {
        button.disabled = true;
        try {
            return await CatapultCsrf.postJson(url, body);
        } catch {
            return null;
        } finally {
            button.disabled = false;
        }
    }

    /** The status of a POST (0 when it couldn't be sent). */
    async function post(button, url, body) {
        return (await send(button, url, body))?.status ?? 0;
    }

    function refusal(root, status) {
        if (status === 404 && root.dataset.labelUnknown) return root.dataset.labelUnknown;
        if (status === 409) return root.dataset.labelConflict;
        if (status === 503) return root.dataset.labelDisabled;
        return status === 400 ? root.dataset.labelInvalid : root.dataset.labelUnavailable;
    }

    async function addDefinition(panel, root, button) {
        const card = button.closest(".admin-tw-create");
        const id = valueOf(card, "id");
        const label = valueOf(card, "label");
        const sortOrder = Number(valueOf(card, "sortOrder") || "0");
        const invalid = [
            ["id", !ID.test(id)],
            ["label", label === "" || label.length > 80],
            ["sortOrder", !Number.isInteger(sortOrder)],
        ].filter(([, bad]) => bad);
        if (invalid.length > 0) {
            invalid.forEach(([name]) => mark(fieldOf(card, name)));
            return;
        }
        const status = await post(button, "/admin/tw", { id, label, sortOrder });
        button.disabled = true; // post() re-enabled it; still busy until the tab reloads
        try {
            if (ok(status)) {
                window.mdui?.snackbar?.({ message: root.dataset.labelCreated });
                await CatapultAdminTabs.reload(panel);
                return;
            }
            CatapultChannel.showError(refusal(root, status));
        } finally {
            button.disabled = false;
        }
    }

    async function rebuild(root, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            const status = await post(button, `/admin/tw/rebuild/${button.dataset.rebuild}`, {});
            if (ok(status)) window.mdui?.snackbar?.({ message: root.dataset.labelRebuildStarted });
            else CatapultChannel.showError(refusal(root, status));
        } finally {
            button.disabled = false;
        }
    }

    // --- the keywords sub-view ---------------------------------------------------------------

    const KEYWORD_MIN = 2;
    const KEYWORD_MAX = 50;
    const APP_ID = /^[0-9]{1,12}$/;
    const keywordsUrl = (root, action) => `/admin/tw/${encodeURIComponent(root.dataset.twId)}/keywords${action}`;

    async function addKeyword(panel, root, button) {
        const field = fieldOf(root, "keyword");
        const keyword = (field.value ?? "").trim();
        if (keyword.length < KEYWORD_MIN || keyword.length > KEYWORD_MAX) return mark(field);
        const status = await post(button, keywordsUrl(root, ""), { keyword });
        button.disabled = true; // still busy until the view has reloaded
        try {
            if (ok(status)) {
                window.mdui?.snackbar?.({ message: root.dataset.labelAdded });
                await CatapultAdminTabs.reload(panel);
                return;
            }
            CatapultChannel.showError(status === 400 ? root.dataset.labelInvalid : refusal(root, status));
        } finally {
            button.disabled = false;
        }
    }

    async function removeKeyword(panel, root, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            // free text: it travels in the body, a path can't carry a "/" or a "%"
            const keyword = button.closest("[data-keyword]").dataset.keyword;
            const status = await post(button, keywordsUrl(root, "/remove"), { keyword });
            button.disabled = true;
            if (ok(status)) {
                window.mdui?.snackbar?.({ message: root.dataset.labelRemoved });
                await CatapultAdminTabs.reload(panel);
                return;
            }
            CatapultChannel.showError(status === 400 ? root.dataset.labelInvalid : refusal(root, status));
        } finally {
            button.disabled = false;
        }
    }

    /** A paragraph made of a label and a value, both as text. */
    function line(label, value, className) {
        const p = document.createElement("p");
        if (className) p.className = className;
        p.textContent = value === undefined ? label : `${label} ${value}`;
        return p;
    }

    function showTest(root, view) {
        const none = root.dataset.labelNone;
        const out = root.querySelector(".admin-tw-test-result");
        const list = items => (items?.length ? items.join(", ") : none);
        const lines = [
            line(root.dataset.labelMatchedKeywords, list(view.matchedKeywords)),
            line(root.dataset.labelMatchedIds, list(view.matchedContentDescriptorIds)),
        ];
        if (view.draftKeywordMatch === true) lines.push(line(root.dataset.labelDraftMatch));
        else if (view.draftKeywordMatch === false) lines.push(line(root.dataset.labelDraftNoMatch));
        lines.push(line(root.dataset.labelNotes, view.notes ? view.notes : none, "admin-tw-test-notes"));
        out.replaceChildren(...lines);
    }

    async function runTest(root, button) {
        const appField = fieldOf(root, "appId");
        const appId = (appField.value ?? "").trim();
        if (!APP_ID.test(appId)) return mark(appField);
        const out = root.querySelector(".admin-tw-test-result");
        const draftKeyword = (fieldOf(root, "keyword")?.value ?? "").trim();
        const answer = await send(button, keywordsUrl(root, "/test"), { appId, draftKeyword });
        let view = null;
        if (answer && ok(answer.status)) {
            try {
                view = await answer.json();
            } catch {
                view = null;
            }
        }
        if (!root.isConnected) return; // the view was left meanwhile
        if (view) showTest(root, view);
        else out.textContent = root.dataset.labelTestError;
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="tw"]');
        if (!panel) return;

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(".admin-tw-add-save, .admin-tw-rebuild,"
                + " .admin-tw-keyword-add, .admin-tw-keyword-remove, .admin-tw-test-run");
            if (!target || !panel.contains(target) || target.disabled) return;
            const keywordsRoot = target.closest(".admin-tw-kw");
            if (keywordsRoot) {
                if (target.matches(".admin-tw-keyword-add")) addKeyword(panel, keywordsRoot, target);
                else if (target.matches(".admin-tw-keyword-remove")) removeKeyword(panel, keywordsRoot, target);
                else runTest(keywordsRoot, target);
                return;
            }
            const root = panel.querySelector(".admin-tw");
            if (!root) return;
            if (target.matches(".admin-tw-add-save")) addDefinition(panel, root, target);
            else rebuild(root, target);
        });
        panel.addEventListener("input", event => event.target.removeAttribute?.("invalid"));
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
