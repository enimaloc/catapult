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
 *   to date in place (but for the form of an open row, which the owner may be editing). A save or a
 *   deletion re-renders the page, which lists the result.
 * - API token card (Account tab): since when a token exists, generate/regenerate (regeneration confirmed: it
 *   invalidates the old token) and revoke. A generated token is shown once, in a read-only field with
 *   a copy button and a curl example on the site's own address (catapult-web relays /api/v2/custom-bindings
 *   to catapult-api); it is kept nowhere but that field.
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
     */
    function setActive(activeId) {
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

        async function save(item, id, game) {
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
            // An edit of the active binding is re-applied on Twitch: its outcome, when it failed, is told.
            let reapply = null;
            try {
                reapply = (await response.json())?.reapplyOutcome ?? null;
            } catch { /* a creation answers the binding alone */ }
            if (reapply && reapply !== "applied") CatapultChannel.showError(refusalText(list, reapply, response.status));
            await CatapultChannel.refresh();
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
            on(".custom-binding-save", () => save(item, id, game));
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
            show(byId("channel-custom-bindings-empty"), !list.querySelector(".custom-binding-item[data-custom-binding-id]"));
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
            if (response.ok) return CatapultChannel.refresh();
            CatapultChannel.showError(refusalText(list, await readCode(response), response.status));
        });
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
     * the owner, or the experiment closed to the channel) has nothing to update.
     */
    function apply(bindings, activeId) {
        const list = byId("channel-custom-bindings-list");
        if (!list) return true;
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
                row = template.content.firstElementChild.cloneNode(true);
                row.dataset.customBindingId = binding.id;
                row.setAttribute("value", binding.id);
                showHeader(row, binding);
                parent.insertBefore(row, next && next.parentElement === parent ? next : null);
                // Once in the page, its switches and fields are upgraded: their properties are theirs.
                fillForm(row, binding, null);
                wired.attachItem(row);
            }
            next = row;
        }
        show(byId("channel-custom-bindings-empty"), bindings.length === 0 && (!fresh || fresh.classList.contains("hidden")));
        // A refusal under the list stays: the owner may be reading it.
        markActive(activeId ?? null);
        return true;
    }

    // The promise of the last /me/api-token answer, kept for the page: /me is the logged-in member, not the
    // channel, so SPA navigations reuse it. Null until fetched, and again after a failure so a later render retries.
    let tokenStatus = null;

    function fetchTokenStatus() {
        if (!tokenStatus) {
            const request = (async () => {
                const response = await fetch("/me/api-token", { headers: { Accept: "application/json" } });
                if (!response.ok) throw new Error(String(response.status));
                return response.json();
            })();
            tokenStatus = request;
            request.catch(() => { if (tokenStatus === request) tokenStatus = null; });
        }
        return tokenStatus;
    }

    function attachToken(card) {
        const status = byId("api-token-status");
        const generate = byId("api-token-generate-btn");
        const revoke = byId("api-token-revoke-btn");
        const reveal = byId("api-token-reveal");
        const value = byId("api-token-value");
        const curl = byId("api-token-curl");
        const dialog = byId("api-token-confirm-dialog");
        let exists = false;
        let pending = null;

        function render(token) {
            exists = !!token?.exists;
            status.textContent = exists
                ? card.dataset.labelSince.replace("{0}", new Date(token.createdAt)
                    .toLocaleString(document.documentElement.lang || undefined))
                : card.dataset.labelNone;
            generate.textContent = exists ? card.dataset.labelRegenerate : card.dataset.labelGenerate;
            show(revoke, exists);
        }

        function forget() {
            value.value = "";
            curl.textContent = "";
            show(reveal, false);
        }

        function showToken(token) {
            value.value = token;
            curl.textContent = `curl -X POST -H "Authorization: Bearer ${token}" `
                + `${window.location.origin}/api/v2/custom-bindings/${encodeURIComponent(card.dataset.exampleName)}/activate`;
            show(reveal, true);
        }

        async function regenerate() {
            const response = await post("/me/api-token", {});
            if (!response) return;
            if (!response.ok) return CatapultChannel.showError(CatapultChannel.actionFailed(response.status));
            const created = await response.json();
            showToken(created.token);
            const generated = { exists: true, createdAt: created.createdAt };
            tokenStatus = Promise.resolve(generated);
            render(generated);
        }

        async function revokeToken() {
            const response = await post("/me/api-token/delete", {});
            if (!response) return;
            if (!response.ok) return CatapultChannel.showError(CatapultChannel.actionFailed(response.status));
            forget();
            const revoked = { exists: false };
            tokenStatus = Promise.resolve(revoked);
            render(revoked);
        }

        function confirm(action) {
            pending = action;
            dialog.querySelectorAll("[data-for]").forEach(el => show(el, el.dataset.for === action));
            dialog.open = true;
        }

        generate.addEventListener("click", () => (exists ? confirm("regenerate") : regenerate()));
        revoke.addEventListener("click", () => confirm("revoke"));
        dialog.querySelector(".api-token-confirm-dismiss")?.addEventListener("click", () => { dialog.open = false; });
        dialog.querySelector(".api-token-confirm-ok")?.addEventListener("click", async () => {
            const action = pending;
            pending = null;
            dialog.open = false;
            await (action === "revoke" ? revokeToken() : regenerate());
        });

        byId("api-token-copy-btn")?.addEventListener("click", async () => {
            try {
                await navigator.clipboard.writeText(value.value);
                CatapultChannel.showError(card.dataset.labelCopied);
            } catch {
                value.select?.();
            }
        });

        // The token itself is never part of the page: only whether one exists.
        (async () => {
            try {
                render(await fetchTokenStatus());
            } catch {
                status.textContent = card.dataset.labelUnavailable ?? "";
            }
        })();
    }

    document.addEventListener("catapult:render", () => {
        const list = byId("channel-custom-bindings-list");
        if (list) attachList(list);
        const token = byId("channel-api-token-card");
        if (token) attachToken(token);
    });

    return { setActive, apply };
})();
