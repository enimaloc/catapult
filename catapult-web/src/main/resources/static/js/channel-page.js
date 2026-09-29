/**
 * Shared helpers for the /channel/{username} dashboard fragment scripts
 * (channel-status.js, channel-bindings.js, channel-connections.js,
 * channel-settings.js, channel-dtdd.js) and for channel-events.js, which
 * patches the DOM in place as each SSE event arrives instead of re-fetching
 * the whole fragment.
 *
 * - username()/baseUrl(): a single place deriving the channel username from the
 *   URL, instead of every file re-deriving `location.pathname.split("/")[2]`.
 * - refresh(): re-renders the current fragment through the SPA router, preserving
 *   the query string (page/status/source filters) instead of dropping it. Only
 *   used where no SSE event yet covers the mutation (see channel-settings.js).
 * - postJson(): wraps CatapultCsrf.postJson and surfaces a visible error when the
 *   mutation didn't succeed, instead of silently re-rendering as if it had.
 */
window.CatapultChannel = (function () {
    function username() {
        return location.pathname.split("/")[2];
    }

    function baseUrl() {
        return `/channel/${username()}`;
    }

    async function refresh() {
        const path = location.pathname.replace(/^\/+/, "") + location.search;
        await navigate(path, false);
    }

    function showError(message) {
        const snackbar = document.createElement("mdui-snackbar");
        snackbar.textContent = message;
        snackbar.open = true;
        document.body.appendChild(snackbar);
    }

    async function postJson(url, body) {
        const response = await CatapultCsrf.postJson(url, body);
        if (!response.ok) {
            showError(`Action failed (${response.status})`);
        }
        return response;
    }

    function setStreamState(live) {
        document.getElementById("channel-state-live").className = live ? "" : "hidden";
        document.getElementById("channel-state-offline").className = !live ? "" : "hidden";
    }

    function setBotState(state) {
        document.getElementById("channel-bot-toggle").checked = state;
    }

    function setCurrentGame(sourceName) {
        const el = document.getElementById("channel-current-game");
        if (el) el.textContent = sourceName;
    }

    function getBindingsElements() {
        return Array.from(document.getElementById("channel-bindings-list").children);
    }

    function getBindingElement(id) {
        return getBindingsElements().filter(el => el.dataset.bindingId === id).at(0);
    }

    function setBindingIgnored(id, enabled) {
        const row = getBindingElement(id);
        if (row) row.querySelector(".binding-ignored-toggle").checked = enabled;
    }

    function setCclEnabled(id, enabled) {
        const row = getBindingElement(id);
        if (row) row.querySelector(".binding-ccl-toggle").checked = enabled;
    }

    function removeBinding(id) {
        const row = getBindingElement(id);
        if (!row) return;
        const list = document.getElementById("channel-bindings-list");
        row.remove();
        if (list.children.length === 0) {
            const empty = document.createElement("mdui-list-item");
            empty.textContent = list.dataset.emptyText;
            list.appendChild(empty);
        }
    }

    function updateBindingGame(id, twitchGameName, ccls) {
        const row = getBindingElement(id);
        if (!row) return;
        row.querySelector(".binding-game-name").textContent = twitchGameName || "—";
        const chips = row.querySelector(".binding-ccl-chips");
        chips.innerHTML = "";
        ccls.forEach(ccl => {
            const chip = document.createElement("mdui-chip");
            chip.textContent = ccl;
            chips.appendChild(chip);
        });
        row.querySelector(".binding-edit-panel").hidden = true;
    }

    function setTwEnabled(id, enabled) {
        const row = getBindingElement(id);
        if (row) row.querySelector(".binding-tw-enabled-checkbox").checked = enabled;
    }

    function setBindingTws(id, tws) {
        const row = getBindingElement(id);
        if (!row) return;
        row.querySelectorAll(".binding-tw-checkbox").forEach(cb => { cb.checked = tws.includes(cb.value); });
        row.querySelector(".binding-tw-reset-btn").classList.toggle("hidden", tws.length === 0);
    }

    function resetBindingTws(id) {
        setBindingTws(id, []);
    }

    function setMinecraftStatus(status, minecraftName) {
        const card = document.querySelector('[data-conn="minecraft"]');
        if (!card) return;
        const body = document.getElementById("minecraft-status-body");
        const actions = document.getElementById("minecraft-status-actions");

        function el(tag, id, text) {
            const e = document.createElement(tag);
            if (id) e.id = id;
            if (text !== undefined) e.textContent = text;
            return e;
        }

        const bodyByStatus = {
            NONE: () => {
                const input = el("input", "minecraft-name-input");
                input.type = "text";
                input.placeholder = card.dataset.namePlaceholder;
                return input;
            },
            PENDING: () => el("span", null, card.dataset.pendingLabel),
            INVITE_REJECTED: () => el("span", null, card.dataset.rejectedLabel),
            ACCEPTED: () => el("mdui-chip", null, minecraftName),
            REMOVED: () => el("span", null, card.dataset.removedLabel),
        };
        const actionsByStatus = {
            NONE: () => el("mdui-segmented-button", "minecraft-enroll-btn", card.dataset.enrollLabel),
            PENDING: () => el("mdui-segmented-button", "minecraft-check-btn", card.dataset.checkLabel),
            INVITE_REJECTED: () => null,
            ACCEPTED: () => el("mdui-segmented-button", "minecraft-disconnect-btn", card.dataset.disconnectLabel),
            REMOVED: () => el("mdui-segmented-button", "minecraft-check-btn", card.dataset.checkLabel),
        };

        body.replaceChildren(...[bodyByStatus[status]?.()].filter(Boolean));
        actions.replaceChildren(...[actionsByStatus[status]?.()].filter(Boolean));
        CatapultConnections.attachMinecraftHandlers();
    }

    function steamSharedLabel(labelText, checked, checkboxId) {
        const div = document.createElement("div");
        const label = document.createElement("label");
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.id = checkboxId;
        checkbox.checked = checked;
        const span = document.createElement("span");
        span.textContent = labelText;
        label.append(checkbox, span);
        div.append(label);
        return div;
    }

    function setSteamTokenSaved(shared) {
        const body = document.getElementById("steam-token-body");
        const actions = document.getElementById("steam-token-actions");
        if (!body || !actions) return;

        body.replaceChildren(steamSharedLabel(body.dataset.sharedLabel, shared, "steam-token-shared"));

        const deleteBtn = document.createElement("mdui-segmented-button");
        deleteBtn.id = "steam-token-delete-btn";
        deleteBtn.textContent = actions.dataset.deleteLabel;
        const saveBtn = actions.querySelector("#steam-token-save-btn");
        if (saveBtn) saveBtn.replaceWith(deleteBtn);
        else actions.prepend(deleteBtn);
        CatapultConnections.attachSteamHandlers();
    }

    function setSteamTokenShared(shared) {
        const el = document.getElementById("steam-token-shared");
        if (el) el.checked = shared;
    }

    function setSteamTokenDeleted() {
        const body = document.getElementById("steam-token-body");
        const actions = document.getElementById("steam-token-actions");
        if (!body || !actions) return;

        const div = document.createElement("div");
        const tokenInput = document.createElement("input");
        tokenInput.type = "password";
        tokenInput.id = "steam-token-input";
        div.append(tokenInput, steamSharedLabel(body.dataset.sharedLabel, false, "steam-token-share-new").firstChild);
        body.replaceChildren(div);

        const saveBtn = document.createElement("mdui-segmented-button");
        saveBtn.id = "steam-token-save-btn";
        saveBtn.textContent = actions.dataset.saveLabel;
        const deleteBtn = actions.querySelector("#steam-token-delete-btn");
        if (deleteBtn) deleteBtn.replaceWith(saveBtn);
        else actions.prepend(saveBtn);
        CatapultConnections.attachSteamHandlers();
    }

    return {
        username, baseUrl, refresh, postJson, showError,
        getBindingsElements, getBindingElement,
        setStreamState, setBotState, setCurrentGame,
        setBindingIgnored, setCclEnabled, removeBinding, updateBindingGame,
        setTwEnabled, setBindingTws, resetBindingTws,
        setMinecraftStatus,
        setSteamTokenSaved, setSteamTokenShared, setSteamTokenDeleted,
    };
})();
