/**
 * The owner's custom bindings, on the channel page (the cards are rendered for the owner only, and
 * only while the experiment is open to the channel).
 *
 * - Overview card: one button per binding triggers it (activation applies the Twitch category at once,
 *   live or not); the active one is highlighted and offers "Deactivate". A refusal is told in a line
 *   under the buttons, in its own text per catapult-api code (data-error-* on the card). The active
 *   binding follows the CustomBindingChangedEvent of the channel stream (setActive, see channel-events.js).
 * - Settings card: the list with edit/delete (deletion confirmed) and one form for creating or editing.
 *   A save re-renders the page, which lists the result. The category is picked through the same search
 *   as the other settings cards (GameSearch).
 * - API token card: since when a token exists, generate/regenerate (regeneration confirmed: it
 *   invalidates the old token) and revoke. A generated token is shown once, in a read-only field with
 *   a copy button and a curl example; it is kept nowhere but that field.
 *
 * Every text goes in through textContent or a property, never as markup.
 */
window.CatapultCustomBindings = (function () {
    const byId = id => document.getElementById(id);
    const show = (el, visible) => el?.classList.toggle("hidden", !visible);

    /** "twitch_unavailable" -> "errorTwitchUnavailable": the data-error-twitch-unavailable of a card. */
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

    /** The text of a refusal: the card's own for the code, else the generic failure with the status. */
    function refusalText(card, code, status) {
        return (code && card.dataset[errorKey(code)]) || CatapultChannel.actionFailed(status);
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

    /** Highlights the active binding (null: none) in the overview card and in the settings list. */
    function setActive(activeId) {
        document.querySelectorAll(".custom-binding-row").forEach(row => {
            const active = !!activeId && row.dataset.customBindingId === activeId;
            row.classList.toggle("is-active", active);
            row.querySelector(".custom-binding-trigger")?.setAttribute("variant", active ? "filled" : "tonal");
            show(row.querySelector(".custom-binding-stop"), active);
        });
        document.querySelectorAll(".custom-binding-item").forEach(item => {
            show(item.querySelector(".custom-binding-active-chip"),
                !!activeId && item.dataset.customBindingId === activeId);
        });
        const error = byId("custom-bindings-error");
        if (error) {
            error.textContent = "";
            show(error, false);
        }
    }

    function attachOverview(card) {
        const error = byId("custom-bindings-error");
        const base = `${CatapultChannel.baseUrl()}/custom-bindings`;

        function tell(message) {
            error.textContent = message;
            show(error, true);
        }

        async function act(url, activeId) {
            const response = await post(url, {});
            if (!response) return tell(CatapultChannel.unreachable());
            if (response.ok) return setActive(activeId);
            tell(refusalText(card, await readCode(response), response.status));
        }

        card.querySelectorAll(".custom-binding-row").forEach(row => {
            const id = row.dataset.customBindingId;
            row.querySelector(".custom-binding-trigger")
                ?.addEventListener("click", () => act(`${base}/${id}/activate`, id));
            row.querySelector(".custom-binding-stop")
                ?.addEventListener("click", () => act(`${base}/deactivate`, null));
        });
    }

    function attachSettings(card) {
        const base = `${CatapultChannel.baseUrl()}/custom-bindings`;
        const name = byId("custom-binding-name");
        const gameId = byId("custom-binding-game-id");
        const gameInput = byId("custom-binding-game-input");
        const uninterruptible = byId("custom-binding-uninterruptible");
        const cancel = byId("custom-binding-cancel-btn");
        const dialog = byId("custom-binding-delete-dialog");
        let editingId = null;
        let deletingId = null;

        const game = GameSearch.attach(gameInput, byId("custom-binding-game-results"),
            `${CatapultChannel.baseUrl()}/games/search`, picked => { gameId.value = picked.id; },
            { initial: { id: gameId.value, name: gameInput.value } });

        const split = value => (value ? value.split(",").filter(Boolean) : []);

        function fill(item) {
            editingId = item?.dataset.customBindingId ?? null;
            name.value = item?.dataset.name ?? "";
            gameId.value = item?.dataset.gameId ?? "";
            game.select(item ? { id: item.dataset.gameId, name: item.dataset.gameName } : null);
            const ccls = new Set(split(item?.dataset.ccls));
            const tws = new Set(split(item?.dataset.tws));
            card.querySelectorAll(".custom-binding-ccl-checkbox").forEach(sw => { sw.checked = ccls.has(sw.value); });
            card.querySelectorAll(".custom-binding-tw-checkbox").forEach(sw => { sw.checked = tws.has(sw.value); });
            uninterruptible.checked = item?.dataset.uninterruptible === "true";
            show(cancel, !!item);
        }

        card.querySelectorAll(".custom-binding-item").forEach(item => {
            item.querySelector(".custom-binding-edit")?.addEventListener("click", () => {
                fill(item);
                name.scrollIntoView?.({ block: "center" });
            });
            item.querySelector(".custom-binding-delete")?.addEventListener("click", () => {
                deletingId = item.dataset.customBindingId;
                dialog.open = true;
            });
        });
        cancel?.addEventListener("click", () => fill(null));

        dialog?.querySelector(".custom-binding-delete-dismiss")?.addEventListener("click", () => { dialog.open = false; });
        dialog?.querySelector(".custom-binding-delete-confirm")?.addEventListener("click", async () => {
            const id = deletingId;
            dialog.open = false;
            if (!id) return;
            const response = await post(`${base}/${id}/delete`, {});
            if (!response) return;
            if (response.ok) return CatapultChannel.refresh();
            CatapultChannel.showError(refusalText(card, await readCode(response), response.status));
        });

        byId("custom-binding-save-btn")?.addEventListener("click", async () => {
            const selected = game.selected();
            const trimmed = (name.value ?? "").trim();
            if (!trimmed || !selected?.id) return CatapultChannel.showError(card.dataset.errorInvalid);
            const response = await post(editingId ? `${base}/${editingId}` : base, {
                name: trimmed,
                twitchGameId: selected.id,
                twitchGameName: selected.name,
                ccls: CatapultChannel.checkedValues(card, ".custom-binding-ccl-checkbox"),
                tws: CatapultChannel.checkedValues(card, ".custom-binding-tw-checkbox"),
                uninterruptible: !!uninterruptible.checked
            });
            if (!response) return;
            if (!response.ok) {
                return CatapultChannel.showError(refusalText(card, await readCode(response), response.status));
            }
            // An edit of the active binding is re-applied on Twitch: its outcome, when it failed, is told.
            let reapply = null;
            try {
                reapply = (await response.json())?.reapplyOutcome ?? null;
            } catch { /* a creation answers the binding alone */ }
            if (reapply && reapply !== "applied") CatapultChannel.showError(refusalText(card, reapply, response.status));
            await CatapultChannel.refresh();
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
                + `${card.dataset.apiBase}/api/v2/custom-bindings/${encodeURIComponent(card.dataset.exampleName)}/activate`;
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
        const overview = byId("channel-custom-bindings-card");
        if (overview) attachOverview(overview);
        const settings = byId("channel-custom-bindings-settings");
        if (settings) attachSettings(settings);
        const token = byId("channel-api-token-card");
        if (token) attachToken(token);
    });

    return { setActive };
})();
