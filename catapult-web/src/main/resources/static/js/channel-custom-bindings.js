/**
 * The owner's custom bindings, on the channel page (rendered for the owner only, and only while the
 * experiment is open to the channel).
 *
 * - Overview list, under the associations and shaped like them: a row per binding, the active one marked.
 *   A row opens its edit form (name, category through the same search as the associations' (GameSearch),
 *   CCLs, TWs, uninterruptible) with activate/deactivate, save and delete (confirmed). "New" reveals an
 *   empty row whose save creates. Activation applies the Twitch category at once, live or not; its refusal
 *   is told in a line under the list, in its own text per catapult-api code (data-error-* on the list). The
 *   active binding follows the CustomBindingChangedEvent of the channel stream (setActive, see
 *   channel-events.js), the list its CustomBindingsUpdatedEvent (apply): a binding it no longer lists
 *   loses its row, one it newly lists gets a copy of the page's row template, the others are brought up
 *   to date in place (but for the form of an open row, which the owner may be editing). Either event published
 *   before the last one applied (its ts: events published apart may arrive out of order) is dropped. A save or a
 *   deletion shows its result at once from its answer (the created row added from the same template, the
 *   edited one brought up to date, the deleted one removed): its live event then finds nothing to change.
 * - The personal API token card (Account tab) is api-token-cards.js's.
 *
 * Every text goes in through textContent or a property, never as markup.
 */
window.CatapultCustomBindings = (function () {
    const byId = id => document.getElementById(id);
    const show = (el, visible) => el?.classList.toggle("hidden", !visible);
    // Properties only written when they change: an equal write still counts as a change of the page.
    const set = (el, property, value) => { if (el && el[property] !== value) el[property] = value; };
    // The list wired last (attachList): its rows' wiring, for the rows apply() adds.
    let wired = null;
    // When the last live event applied to the list or its active binding was published (its ts, epoch millis).
    let appliedTs = -Infinity;

    /**
     * Whether an event published at `ts` is to be applied: not when published before the last one applied.
     * Without a ts (an older catapult-api, a catch-up's state, the page's own answer) it always is.
     */
    function inOrder(ts) {
        if (typeof ts !== "number") return true;
        if (ts < appliedTs) return false;
        appliedTs = ts;
        return true;
    }

    /** "twitch_unavailable" -> "errorTwitchUnavailable": the data-error-twitch-unavailable of the list. */
    function errorKey(code) {
        return "error" + String(code).replace(/(^|_)([a-z])/g, (_, __, c) => c.toUpperCase());
    }

    async function readCode(response) {
        try {
            return (await response.json())?.code ?? null;
        } catch {
            return null;
        }
    }

    /** The text of a refusal: the list's own for the code, else the generic failure with the status. */
    function refusalText(root, code, status) {
        return (code && root.dataset[errorKey(code)]) || CatapultChannel.actionFailed(status);
    }

    /** Posts and answers the response, or null (told) when the request could not be sent. */
    async function post(url, body) {
        try {
            return await CatapultCsrf.postJson(url, body);
        } catch {
            CatapultChannel.showError(CatapultChannel.unreachable());
            return null;
        }
    }

    /**
     * Marks the active binding (null: none) and offers to deactivate it, the others to activate. An activation
     * (made here or elsewhere) also takes away a refusal told under the list; the list's own update (apply) leaves it.
     * `ts`: when the live event telling it was published (see inOrder).
     */
    function setActive(activeId, ts) {
        if (!inOrder(ts)) return;
        markActive(activeId);
        const error = byId("custom-bindings-error");
        if (error) {
            error.textContent = "";
            show(error, false);
        }
    }

    function markActive(activeId) {
        document.querySelectorAll(".custom-binding-item[data-custom-binding-id]").forEach(item => {
            const active = !!activeId && item.dataset.customBindingId === activeId;
            item.classList.toggle("is-active", active);
            show(item.querySelector(".custom-binding-active-chip"), active);
            show(item.querySelector(".custom-binding-activate"), !active);
            show(item.querySelector(".custom-binding-stop"), active);
        });
    }

    function attachList(list) {
        const base = `${CatapultChannel.baseUrl()}/custom-bindings`;
        const error = byId("custom-bindings-error");
        const dialog = byId("custom-binding-delete-dialog");
        const collapse = list.querySelector("mdui-collapse");
        let deletingId = null;
        // Each row's category search, for apply() to show another category in.
        const games = new WeakMap();

        function tell(message) {
            error.textContent = message;
            show(error, true);
        }

        /**
         * Runs `task` with `button` disabled, so a double click sends one request. `task` is told whether the
         * focus was in the button's row beforehand: disabling a focused button may blur it.
         */
        async function busy(button, task) {
            if (button.disabled) return;
            const focused = !!button.closest(".custom-binding-item")?.contains(document.activeElement);
            button.disabled = true;
            try {
                await task(focused);
            } finally {
                button.disabled = false;
            }
        }

        async function act(item, url, activeId, focused) {
            const response = await post(url, {});
            if (!response) return tell(CatapultChannel.unreachable());
            if (!response.ok) return tell(refusalText(list, await readCode(response), response.status));
            setActive(activeId);
            // The clicked button is hidden now: keep the keyboard user in the row, on the one that took its place.
            if (focused) item.querySelector(activeId ? ".custom-binding-stop" : ".custom-binding-activate")?.focus?.();
        }

        async function save(item, id, game, focused) {
            const selected = game.selected();
            const trimmed = (item.querySelector(".custom-binding-name").value ?? "").trim();
            if (!trimmed || !selected?.id) return CatapultChannel.showError(list.dataset.errorInvalid);
            const response = await post(id ? `${base}/${id}` : base, {
                name: trimmed,
                twitchGameId: selected.id,
                twitchGameName: selected.name,
                ccls: CatapultChannel.checkedValues(item, ".custom-binding-ccl-checkbox"),
                tws: CatapultChannel.checkedValues(item, ".custom-binding-tw-checkbox"),
                uninterruptible: !!item.querySelector(".custom-binding-uninterruptible").checked
            });
            if (!response) return;
            if (!response.ok) {
                return CatapultChannel.showError(refusalText(list, await readCode(response), response.status));
            }
            let answer = null;
            try {
                answer = await response.json();
            } catch { /* no binding to show: its live event will */ }
            // An edit of the active binding is re-applied on Twitch: its outcome, when it failed, is told.
            const reapply = answer?.reapplyOutcome ?? null;
            if (reapply && reapply !== "applied") CatapultChannel.showError(refusalText(list, reapply, response.status));
            // A creation answers the binding alone, an edit the binding and its re-application.
            const binding = id ? answer?.binding : answer;
            if (!binding?.id) return;
            if (id) return updateRow(item, binding, wired.games.get(item));
            const row = rowOf(list, binding.id) ?? addRow(list, binding, item);
            if (!row) return;
            // The "New" row closes, emptied; the keyboard user goes on from the created row.
            if (collapse && collapse.value === item.getAttribute("value")) collapse.value = undefined;
            item.classList.remove("is-open");
            dismissNew(item, game);
            if (focused) row.querySelector("[slot=header]")?.focus?.();
        }

        // mdui-collapse fires open/close but doesn't reflect them on the item: mirror it as .is-open, which
        // the CSS shared with the associations list squares the joined header/body corners on.
        function attachItem(item) {
            const id = item.dataset.customBindingId ?? null;
            const gameInput = item.querySelector(".custom-binding-game-input");
            const gameId = item.querySelector(".custom-binding-game-id");
            const game = GameSearch.attach(gameInput, item.querySelector(".custom-binding-game-results"),
                `${CatapultChannel.baseUrl()}/games/search`, picked => { gameId.value = picked.id; },
                { initial: { id: gameId.value, name: gameInput.value } });
            games.set(item, game);

            item.addEventListener("open", event => { if (event.target === item) item.classList.add("is-open"); });
            item.addEventListener("close", event => {
                if (event.target !== item) return;
                item.classList.remove("is-open");
                if (item.classList.contains("custom-binding-new")) dismissNew(item, game);
            });

            const on = (selector, handler) => item.querySelector(selector)
                ?.addEventListener("click", event => busy(event.currentTarget, handler));
            on(".custom-binding-activate", focused => act(item, `${base}/${id}/activate`, id, focused));
            on(".custom-binding-stop", focused => act(item, `${base}/deactivate`, null, focused));
            on(".custom-binding-save", focused => save(item, id, game, focused));
            item.querySelector(".custom-binding-delete")?.addEventListener("click", () => {
                deletingId = id;
                dialog.open = true;
            });
        }

        list.querySelectorAll(".custom-binding-item").forEach(attachItem);
        wired = {
            list, attachItem, games,
            /** A row gone: the deletion it was being confirmed for has nothing left to delete. */
            removed(id) {
                if (deletingId !== id) return;
                deletingId = null;
                if (dialog) dialog.open = false;
            },
        };

        /** The closed "New" row goes away emptied; the empty notice comes back when no binding is listed. */
        function dismissNew(fresh, game) {
            show(fresh, false);
            fresh.querySelector(".custom-binding-name").value = "";
            fresh.querySelector(".custom-binding-game-id").value = "";
            game.select(null);
            fresh.querySelectorAll("mdui-switch").forEach(sw => { sw.checked = false; });
            showEmpty(list);
        }

        byId("custom-binding-new-btn")?.addEventListener("click", () => {
            const fresh = list.querySelector(".custom-binding-new");
            if (!fresh) return;
            show(fresh, true);
            show(byId("channel-custom-bindings-empty"), false);
            if (collapse) collapse.value = fresh.getAttribute("value");
            fresh.classList.add("is-open");
            fresh.querySelector(".custom-binding-name")?.focus?.();
        });

        dialog?.querySelector(".custom-binding-delete-dismiss")?.addEventListener("click", () => { dialog.open = false; });
        dialog?.querySelector(".custom-binding-delete-confirm")?.addEventListener("click", async () => {
            const id = deletingId;
            dialog.open = false;
            if (!id) return;
            const response = await post(`${base}/${id}/delete`, {});
            if (!response) return;
            if (!response.ok) return CatapultChannel.showError(refusalText(list, await readCode(response), response.status));
            rowOf(list, id)?.remove();
            showEmpty(list);
        });
    }

    /** The row of the saved binding `id` in `list`, if it has one. */
    function rowOf(list, id) {
        return Array.from(list.querySelectorAll(".custom-binding-item[data-custom-binding-id]"))
            .find(row => row.dataset.customBindingId === id) ?? null;
    }

    /** The empty notice, shown when no binding is listed and the "New" row isn't open in its place. */
    function showEmpty(list) {
        const fresh = list.querySelector(".custom-binding-new");
        show(byId("channel-custom-bindings-empty"), !list.querySelector(".custom-binding-item[data-custom-binding-id]")
            && (!fresh || fresh.classList.contains("hidden")));
    }

    /**
     * Adds a row for `binding`, a copy of the page's row template, before `before` (null: last), wired like
     * the others. Null without the template.
     */
    function addRow(list, binding, before) {
        const template = byId("custom-binding-item-template");
        if (!template || wired?.list !== list) return null;
        const parent = before?.parentElement ?? list.querySelector("mdui-collapse");
        const row = template.content.firstElementChild.cloneNode(true);
        row.dataset.customBindingId = binding.id;
        row.setAttribute("value", binding.id);
        showHeader(row, binding);
        parent.insertBefore(row, before && before.parentElement === parent ? before : null);
        // Once in the page, its switches and fields are upgraded: their properties are theirs.
        fillForm(row, binding, null);
        wired.attachItem(row);
        return row;
    }

    /** Brings `row` to `binding`, header and form; `game`, its category search. */
    function updateRow(row, binding, game) {
        showHeader(row, binding);
        fillForm(row, binding, game);
    }

    /** Shows `binding` in its row's header. */
    function showHeader(item, binding) {
        set(item.querySelector(".custom-binding-name-label"), "textContent", binding.name ?? "");
        set(item.querySelector(".custom-binding-game-label"), "textContent", binding.twitchGameName || "—");
        show(item.querySelector(".custom-binding-uninterruptible-chip"), !!binding.uninterruptible);
    }

    /** Puts `binding` in its row's form; `game`, its category search once wired. */
    function fillForm(item, binding, game) {
        set(item.querySelector(".custom-binding-name"), "value", binding.name ?? "");
        set(item.querySelector(".custom-binding-game-id"), "value", binding.twitchGameId ?? "");
        const category = { id: binding.twitchGameId, name: binding.twitchGameName ?? "" };
        if (game) {
            if (game.selected()?.id !== category.id || game.selected()?.name !== category.name) game.select(category);
        } else {
            set(item.querySelector(".custom-binding-game-input"), "value", binding.twitchGameName ?? "");
        }
        const ccls = binding.ccls ?? [];
        const tws = binding.tws ?? [];
        const value = sw => sw.value ?? sw.getAttribute("value");
        item.querySelectorAll(".custom-binding-ccl-checkbox").forEach(sw => set(sw, "checked", ccls.includes(value(sw))));
        item.querySelectorAll(".custom-binding-tw-checkbox").forEach(sw => set(sw, "checked", tws.includes(value(sw))));
        set(item.querySelector(".custom-binding-uninterruptible"), "checked", !!binding.uninterruptible);
    }

    /**
     * Brings the list to `bindings` (all of them, oldest first, as a CustomBindingsUpdatedEvent carries them) and
     * `activeId`. False when it can't be done in place, for the page to be re-rendered: no bindings (an older
     * catapult-api), the list not wired yet, or no row template to add one from. A page without the list (anyone but
     * the owner, or the experiment closed to the channel) has nothing to update, nor has an event published before the
     * last one applied (`ts`, see inOrder): what it tells is outdated.
     */
    function apply(bindings, activeId, ts) {
        const list = byId("channel-custom-bindings-list");
        if (!list) return true;
        if (!inOrder(ts)) return true;
        if (!Array.isArray(bindings) || wired?.list !== list) return false;
        const rows = new Map(Array.from(list.querySelectorAll(".custom-binding-item[data-custom-binding-id]"),
            row => [row.dataset.customBindingId, row]));
        const template = byId("custom-binding-item-template");
        if (bindings.some(binding => !rows.has(binding.id)) && !template) return false;

        const listed = new Set(bindings.map(binding => binding.id));
        rows.forEach((row, id) => {
            if (listed.has(id)) return;
            row.remove();
            wired.removed(id);
        });
        // Added in the list's order, before the next one listed (the "New" row for the last ones).
        const fresh = list.querySelector(".custom-binding-new");
        const parent = fresh?.parentElement ?? list.querySelector("mdui-collapse");
        let next = fresh;
        for (let i = bindings.length - 1; i >= 0; i--) {
            const binding = bindings[i];
            let row = rows.get(binding.id);
            if (row) {
                showHeader(row, binding);
                // The owner may be editing an open row: its form keeps what they typed.
                if (!row.classList.contains("is-open") && !row.contains(document.activeElement)) {
                    fillForm(row, binding, wired.games.get(row));
                }
            } else {
                row = addRow(list, binding, next && next.parentElement === parent ? next : null);
            }
            next = row;
        }
        showEmpty(list);
        // A refusal under the list stays: the owner may be reading it.
        markActive(activeId ?? null);
        return true;
    }

    document.addEventListener("catapult:render", () => {
        const list = byId("channel-custom-bindings-list");
        if (list) attachList(list);
    });

    return { setActive, apply };
})();
