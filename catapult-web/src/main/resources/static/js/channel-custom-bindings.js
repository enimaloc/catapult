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
 *   channel-events.js). A save or a deletion re-renders the page, which lists the result.
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

    /** Marks the active binding (null: none) and offers to deactivate it, the others to activate. */
    function setActive(activeId) {
        document.querySelectorAll(".custom-binding-item[data-custom-binding-id]").forEach(item => {
            const active = !!activeId && item.dataset.customBindingId === activeId;
            item.classList.toggle("is-active", active);
            show(item.querySelector(".custom-binding-active-chip"), active);
            show(item.querySelector(".custom-binding-activate"), !active);
            show(item.querySelector(".custom-binding-stop"), active);
        });
        const error = byId("custom-bindings-error");
        if (error) {
            error.textContent = "";
            show(error, false);
        }
    }

    function attachList(list) {
        const base = `${CatapultChannel.baseUrl()}/custom-bindings`;
        const error = byId("custom-bindings-error");
        const dialog = byId("custom-binding-delete-dialog");
        const collapse = list.querySelector("mdui-collapse");
        let deletingId = null;

        function tell(message) {
            error.textContent = message;
            show(error, true);
        }

        async function act(url, activeId) {
            const response = await post(url, {});
            if (!response) return tell(CatapultChannel.unreachable());
            if (response.ok) return setActive(activeId);
            tell(refusalText(list, await readCode(response), response.status));
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
        list.querySelectorAll(".custom-binding-item").forEach(item => {
            item.addEventListener("open", () => item.classList.add("is-open"));
            item.addEventListener("close", () => item.classList.remove("is-open"));

            const id = item.dataset.customBindingId ?? null;
            const gameInput = item.querySelector(".custom-binding-game-input");
            const gameId = item.querySelector(".custom-binding-game-id");
            const game = GameSearch.attach(gameInput, item.querySelector(".custom-binding-game-results"),
                `${CatapultChannel.baseUrl()}/games/search`, picked => { gameId.value = picked.id; },
                { initial: { id: gameId.value, name: gameInput.value } });

            item.querySelector(".custom-binding-activate")
                ?.addEventListener("click", () => act(`${base}/${id}/activate`, id));
            item.querySelector(".custom-binding-stop")
                ?.addEventListener("click", () => act(`${base}/deactivate`, null));
            item.querySelector(".custom-binding-save")?.addEventListener("click", () => save(item, id, game));
            item.querySelector(".custom-binding-delete")?.addEventListener("click", () => {
                deletingId = id;
                dialog.open = true;
            });
        });

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
            render({ exists: true, createdAt: created.createdAt });
        }

        async function revokeToken() {
            const response = await post("/me/api-token/delete", {});
            if (!response) return;
            if (!response.ok) return CatapultChannel.showError(CatapultChannel.actionFailed(response.status));
            forget();
            render({ exists: false });
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
                const response = await fetch("/me/api-token", { headers: { Accept: "application/json" } });
                if (!response.ok) throw new Error(String(response.status));
                render(await response.json());
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

    return { setActive };
})();
