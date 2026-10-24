/**
 * The admin game mappings tab. Its two lists page on their own (data-gm-page / data-gm-missing-page): a page
 * asked for, or a change made, re-fetches the fragment with both pages (kept on the tab root's data-page /
 * data-missing-page) and swaps only [data-gm-lists], so an open dialog stays as it is.
 *
 * The form (a dialog) creates a mapping (new, or from a missing game's row: its platform and ID filled in) or
 * changes one (from its row). Its game field suggests the platform's detected games as the ID or name is typed
 * ("name — ID · Streamers: n"; picking one fills the ID, any ID typed is kept as is); its category field searches
 * Twitch through GameSearch. The CCLs and TWs stay IGDB's (null) unless their switch replaces them with the boxes
 * checked (even none). After a save the form shows what changed and turns into the saved mapping's edit form.
 * A deletion asks first, in its own dialog. A refusal is told by its status, never by catapult-api's message
 * (catapult-web relays none): 400 invalid, 404 gone, 409 already mapped, anything else unavailable.
 * Text from data only ever reaches the page through textContent. Listeners are delegated to the panel.
 */
window.CatapultAdminGameMappings = (function () {
    const MIN_QUERY_LENGTH = 2;
    const DEBOUNCE_MS = 300;
    const BASE = "/admin/game-mappings";
    const ok = status => status >= 200 && status < 300;

    /** `template` with each {key} replaced by `values[key]`. */
    function fill(template, values) {
        return (template ?? "").replace(/\{(\w+)}/g, (match, key) => key in values ? String(values[key]) : match);
    }

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
                // no body (an error relayed without one)
            }
            return { status: response.status, body: json };
        } catch {
            return { status: 0, body: null };
        }
    }

    /** The label of a refused action, by its status. */
    function refusal(root, status) {
        return root.dataset[`labelError-${status}`] ?? root.dataset.labelUnavailable;
    }

    /** "Bindings updated: n · Live channels updated: n · Failures: n", worded to read right for any count. */
    function resultText(root, result) {
        return fill(root.dataset.labelResult, {
            bindings: result?.bindingsUpdated ?? 0,
            live: result?.liveChannelsUpdated ?? 0,
            failed: result?.liveChannelsFailed ?? 0
        });
    }

    /** Swaps the lists for those of `page` / `missingPage` (the shown ones when left out); a page emptied by a deletion steps back. */
    async function reload(panel, page, missingPage) {
        const root = panel.querySelector(".admin-game-mappings");
        if (!root) return;
        page = page ?? Number(root.dataset.page ?? 0);
        missingPage = missingPage ?? Number(root.dataset.missingPage ?? 0);
        let fresh = null;
        let freshRoot = null;
        try {
            const response = await fetch(`/spa/admin/game-mappings?page=${page}&missingPage=${missingPage}`);
            if (response.ok) {
                const doc = new DOMParser().parseFromString(await response.text(), "text/html");
                freshRoot = doc.querySelector(".admin-game-mappings");
                fresh = doc.querySelector("[data-gm-lists]");
            }
        } catch {
            // not fetched: told below
        }
        const current = root.querySelector("[data-gm-lists]");
        if (!fresh || !current) {
            CatapultChannel.showError(root.dataset.labelUnavailable);
            return;
        }
        const emptied = (selector, number) => number > 0 && !fresh.querySelector(selector) && !fresh.querySelector("[data-admin-retry]");
        if (emptied(".admin-gm-row:not(.admin-row-head)", page)) return reload(panel, page - 1, missingPage);
        if (emptied(".admin-gm-missing-row:not(.admin-row-head)", missingPage)) return reload(panel, page, missingPage - 1);
        current.replaceWith(fresh);
        root.dataset.page = freshRoot?.dataset.page ?? String(page);
        root.dataset.missingPage = freshRoot?.dataset.missingPage ?? String(missingPage);
    }

    /** The form's fields and its state (the mapping edited, the category picked), wired once per dialog. */
    const forms = new WeakMap();

    function formOf(panel) {
        const dialog = panel.querySelector("#admin-gm-dialog");
        if (!dialog) return null;
        let form = forms.get(dialog);
        if (form) return form;
        const root = panel.querySelector(".admin-game-mappings");
        const el = dialog.querySelector(".admin-gm-form");
        form = {
            dialog,
            root,
            title: el.querySelector(".admin-gm-form-title"),
            platform: el.querySelector('[name="sourceType"]'),
            sourceId: el.querySelector('[name="sourceId"]'),
            gameName: el.querySelector(".admin-gm-game-name"),
            suggestions: el.querySelector(".admin-gm-suggestions"),
            category: el.querySelector('[name="category"]'),
            applyLive: el.querySelector('[name="applyLive"]'),
            error: el.querySelector(".admin-gm-error"),
            result: el.querySelector(".admin-gm-result"),
            save: el.querySelector(".admin-gm-save"),
            overrides: {
                ccls: el.querySelector('[data-gm-override="ccls"]'),
                tws: el.querySelector('[data-gm-override="tws"]')
            },
            /** Override values that have no box (a CCL or TW gone since): kept as they are on a save. */
            kept: { ccls: [], tws: [] },
            id: null
        };
        form.search = GameSearch.attach(form.category, el.querySelector(".admin-gm-category-results"),
            `${BASE}/categories`, () => hideMessages(form), { initial: null });
        wireSuggestions(form);
        form.platform.addEventListener("change", () => {
            showFormat(form);
            if (form.platform.value === "MINECRAFT" && form.sourceId.value.trim() === "") form.sourceId.value = "minecraft";
            // The suggestions shown, waiting or on their way are the old platform's: dropped, then looked up again.
            form.dropSuggestions();
            form.searchGames();
        });
        Object.values(form.overrides).forEach(fieldset => {
            fieldset.querySelector(".admin-gm-override-switch").addEventListener("change", () => showChoices(fieldset));
        });
        forms.set(dialog, form);
        return form;
    }

    /**
     * The game field's suggestions: the platform's detected games matching what is typed, the latest answer only.
     * Sets `form.searchGames` (looks the typed text up on the platform shown) and `form.dropSuggestions` (empties
     * the list and drops any lookup waiting or in flight).
     */
    function wireSuggestions(form) {
        let timer;
        let latest = 0;
        form.dropSuggestions = () => {
            clearTimeout(timer);
            latest++;
            form.suggestions.replaceChildren();
        };
        form.searchGames = () => {
            clearTimeout(timer);
            const query = ++latest;
            const q = form.sourceId.value.trim();
            form.gameName.textContent = "";
            if (q.length < MIN_QUERY_LENGTH) {
                form.suggestions.replaceChildren();
                return;
            }
            const sourceType = form.platform.value;
            timer = setTimeout(async () => {
                let games = null;
                try {
                    const response = await fetch(`${BASE}/detected?sourceType=${encodeURIComponent(sourceType)}&q=${encodeURIComponent(q)}`);
                    if (response.ok) games = await response.json();
                } catch {
                    // told as no suggestion
                }
                if (query !== latest) return;
                form.suggestions.replaceChildren(...(Array.isArray(games) ? games : []).map(game => {
                    const item = document.createElement("mdui-list-item");
                    item.textContent = fill(form.root.dataset.labelSuggestion,
                        { name: game.sourceName ?? game.sourceId, id: game.sourceId, n: game.streamerCount });
                    item.addEventListener("click", () => {
                        form.dropSuggestions();
                        form.sourceId.value = game.sourceId;
                        form.gameName.textContent = game.sourceName ?? "";
                    });
                    return item;
                }));
            }, DEBOUNCE_MS);
        };
        form.sourceId.addEventListener("input", form.searchGames);
    }

    function showFormat(form) {
        const item = Array.from(form.platform.querySelectorAll("mdui-menu-item")).find(i => i.value === form.platform.value);
        form.sourceId.helper = item?.dataset.format ?? "";
    }

    function showChoices(fieldset) {
        fieldset.querySelector(".admin-gm-choices").hidden = !fieldset.querySelector(".admin-gm-override-switch").checked;
    }

    function hideMessages(form) {
        form.error.hidden = true;
        form.result.hidden = true;
    }

    function tell(element, text) {
        element.textContent = text;
        element.hidden = false;
    }

    /** Sets one override fieldset to `values` (null: IGDB's). */
    function setOverride(form, key, values) {
        const fieldset = form.overrides[key];
        fieldset.querySelector(".admin-gm-override-switch").checked = values !== null;
        const boxes = Array.from(fieldset.querySelectorAll("mdui-checkbox"));
        const known = new Set(boxes.map(b => b.value));
        boxes.forEach(b => b.checked = values !== null && values.includes(b.value));
        form.kept[key] = values === null ? [] : values.filter(v => !known.has(v));
        showChoices(fieldset);
    }

    /** One override's values to send: null when IGDB's are kept, else the boxes checked (and the values without one). */
    function overrideOf(form, key) {
        const fieldset = form.overrides[key];
        if (!fieldset.querySelector(".admin-gm-override-switch").checked) return null;
        const checked = Array.from(fieldset.querySelectorAll("mdui-checkbox")).filter(b => b.checked).map(b => b.value);
        return [...checked, ...form.kept[key]];
    }

    /** Fills the form with `mapping` (`{ id?, sourceType, sourceId, sourceName, twitchGameId, twitchGameName, ccls, tws }`) and opens it. */
    function open(form, mapping) {
        form.id = mapping.id ?? null;
        form.title.textContent = form.root.dataset[form.id ? "labelEditTitle" : "labelCreateTitle"];
        form.platform.value = mapping.sourceType ?? "STEAM";
        showFormat(form);
        form.sourceId.value = mapping.sourceId ?? "";
        form.gameName.textContent = mapping.sourceName ?? "";
        form.dropSuggestions();
        form.search.select(mapping.twitchGameId ? { id: mapping.twitchGameId, name: mapping.twitchGameName } : null);
        setOverride(form, "ccls", mapping.ccls ?? null);
        setOverride(form, "tws", mapping.tws ?? null);
        form.applyLive.checked = false;
        hideMessages(form);
        form.dialog.open = true;
    }

    /** A row's mapping, read off its data attributes (an override's absence: IGDB's). */
    function mappingOf(row) {
        const list = (name) => row.hasAttribute(`data-${name}-override`)
            ? (row.dataset[name] ?? "").split(",").filter(v => v !== "") : null;
        return {
            id: row.dataset.id,
            sourceType: row.dataset.sourceType,
            sourceId: row.dataset.sourceId,
            sourceName: row.dataset.sourceName,
            twitchGameId: row.dataset.twitchGameId,
            twitchGameName: row.dataset.twitchGameName,
            ccls: list("ccls"),
            tws: list("tws")
        };
    }

    async function save(panel, form) {
        hideMessages(form);
        const sourceId = form.sourceId.value.trim();
        const category = form.search.selected();
        if (sourceId === "") return tell(form.error, form.root.dataset.labelErrorId);
        if (!category) return tell(form.error, form.root.dataset.labelErrorCategory);
        const body = {
            sourceType: form.platform.value,
            sourceId,
            twitchGameId: category.id,
            twitchGameName: category.name,
            ccls: overrideOf(form, "ccls"),
            tws: overrideOf(form, "tws"),
            applyLive: form.applyLive.checked
        };
        form.save.disabled = true;
        const { status, body: result } = form.id
            ? await send("PUT", `${BASE}/${encodeURIComponent(form.id)}`, body)
            : await send("POST", BASE, body);
        form.save.disabled = false;
        if (!ok(status)) {
            tell(form.error, refusal(form.root, status));
            if (status === 404) reload(panel);
            return;
        }
        tell(form.result, resultText(form.root, result));
        const saved = result?.mapping;
        if (saved?.id) {
            form.id = saved.id;
            form.title.textContent = form.root.dataset.labelEditTitle;
        }
        form.applyLive.checked = false;
        await reload(panel);
    }

    /** The deletion dialog, asking first (applyLive unchecked) for the row's mapping. */
    function askDelete(panel, row) {
        const dialog = panel.querySelector("#admin-gm-delete-dialog");
        const root = panel.querySelector(".admin-game-mappings");
        const name = row.dataset.sourceName || row.dataset.twitchGameName || row.dataset.sourceId;
        dialog.querySelector(".admin-gm-delete-title").textContent = fill(root.dataset.labelDeleteTitle, { name });
        dialog.querySelector('[name="applyLive"]').checked = false;
        dialog.dataset.id = row.dataset.id;
        dialog.open = true;
    }

    async function confirmDelete(panel, button) {
        const dialog = panel.querySelector("#admin-gm-delete-dialog");
        const root = panel.querySelector(".admin-game-mappings");
        const applyLive = dialog.querySelector('[name="applyLive"]').checked;
        button.disabled = true;
        const { status, body } = await send("DELETE", `${BASE}/${encodeURIComponent(dialog.dataset.id)}?applyLive=${applyLive}`);
        button.disabled = false;
        if (ok(status)) {
            dialog.open = false;
            window.mdui?.snackbar?.({ message: `${root.dataset.labelDeleted} ${resultText(root, body)}` });
            return reload(panel);
        }
        CatapultChannel.showError(refusal(root, status));
        if (status === 404) {
            dialog.open = false;
            reload(panel);
        }
    }

    const attached = new WeakSet();

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="game-mappings"]');
        if (!panel || attached.has(panel)) return;
        attached.add(panel);

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(
                "[data-gm-page], [data-gm-missing-page], [data-gm-edit], [data-gm-delete], [data-gm-create], #admin-gm-new, "
                + ".admin-gm-save, .admin-gm-confirm-delete, .admin-gm-close");
            if (!target || !panel.contains(target) || target.disabled) return;
            if (target.matches("[data-gm-page]")) {
                reload(panel, Number(target.dataset.gmPage), undefined);
            } else if (target.matches("[data-gm-missing-page]")) {
                reload(panel, undefined, Number(target.dataset.gmMissingPage));
            } else if (target.matches("[data-gm-edit]")) {
                open(formOf(panel), mappingOf(target.closest(".admin-gm-row")));
            } else if (target.matches("[data-gm-delete]")) {
                askDelete(panel, target.closest(".admin-gm-row"));
            } else if (target.matches("[data-gm-create]")) {
                open(formOf(panel), {
                    sourceType: target.dataset.sourceType, sourceId: target.dataset.sourceId,
                    sourceName: target.dataset.sourceName, ccls: null, tws: null
                });
            } else if (target.matches("#admin-gm-new")) {
                open(formOf(panel), { sourceType: "STEAM", ccls: null, tws: null });
            } else if (target.matches(".admin-gm-save")) {
                save(panel, formOf(panel));
            } else if (target.matches(".admin-gm-confirm-delete")) {
                confirmDelete(panel, target);
            } else {
                target.closest("mdui-dialog").open = false;
            }
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach, fill };
})();
