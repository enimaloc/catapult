/**
 * The Epic bot pool card of the admin "Keys & limits" tab. "Add a bot" (and "Re-authenticate", shown only on a
 * REAUTH_REQUIRED bot) opens the login box: a link to Epic's code page (rendered by the server, opening in a new tab) and
 * a field where the admin pastes what that page shows (the whole text or just the code), which "Validate" posts, with the
 * bot's id for a re-authentication. Enable / disable, lift "full", the fill order and delete (after a confirmation) all
 * post to AdminEpicController, the button disabled until the list is patched (admin-tabs.js).
 *
 * A refusal is told inline under the card: catapult-api's own message when it sent one (an expired code, another
 * account), else a text by the status. Those messages only ever reach the page through textContent, never innerHTML.
 *
 * The pasted text holds a one-time authorization code: it is only sent in the POST body, never logged, and the field is
 * emptied once sent. The login box sits outside the list, so patching the tab keeps it. Listeners are delegated to the
 * panel: its content comes and goes.
 */
window.CatapultAdminEpic = (function () {
    const BASE = "/admin/epic-accounts";
    const ok = status => status >= 200 && status < 300;

    /** The status of a POST (0 when it couldn't be sent) and its JSON answer (null when it has none). */
    async function post(url, body) {
        try {
            const res = await CatapultCsrf.postJson(url, body);
            let data = null;
            try {
                data = await res.json();
            } catch {
                // an empty answer (204)
            }
            return { status: res.status, data };
        } catch {
            return { status: 0, data: null };
        }
    }

    const panelOf = () => document.querySelector('mdui-tab-panel[value="keys"]');

    function refusal(card, { status, data }) {
        if (typeof data?.error === "string" && data.error) return data.error;
        if (status === 409) return card.dataset.labelConflict;
        if (status === 404) return card.dataset.labelUnknown;
        if (status === 400) return card.dataset.labelInvalid;
        return card.dataset.labelUnavailable;
    }

    function show(card, text, error) {
        const message = card.querySelector(".admin-epic-message");
        if (!message) return;
        message.textContent = text ?? "";
        message.classList.toggle("admin-epic-error", !!error);
        message.hidden = !text;
    }

    /** Posts an action, holding the button disabled until the list is patched. */
    async function act(panel, card, button, url, body, doneLabel) {
        button.disabled = true;
        try {
            const result = await post(url, body);
            if (ok(result.status)) {
                show(card, "", false);
                window.mdui?.snackbar?.({ message: doneLabel });
                await CatapultAdminTabs.patch(panel);
                return;
            }
            show(card, refusal(card, result), true);
            if (result.status === 404 || result.status === 409) await CatapultAdminTabs.patch(panel);
        } finally {
            button.disabled = false;
        }
    }

    async function remove(panel, card, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            const root = card.closest(".admin-view") ?? card;
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            await act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/delete`, {}, card.dataset.labelDeleted);
        } finally {
            button.disabled = false;
        }
    }

    function closeLogin(card) {
        const box = card.querySelector(".admin-epic-login");
        if (!box) return;
        delete box.dataset.accountId;
        const field = box.querySelector('[name="code"]');
        if (field) field.value = "";
        box.hidden = true;
    }

    /** Opens the login box for a new bot, or for `accountId`'s re-authentication. */
    function openLogin(card, accountId) {
        const box = card.querySelector(".admin-epic-login");
        if (!box) return;
        if (accountId) box.dataset.accountId = accountId;
        else delete box.dataset.accountId;
        const field = box.querySelector('[name="code"]');
        if (field) field.value = "";
        box.hidden = false;
        show(card, "", false);
    }

    async function submitLogin(panel, card, button) {
        const box = button.closest(".admin-epic-login");
        const field = box?.querySelector('[name="code"]');
        const code = (field?.value ?? "").trim();
        if (!code) return show(card, card.dataset.labelInvalid, true);
        const accountId = box.dataset.accountId;
        button.disabled = true;
        try {
            const result = await post(BASE, accountId ? { code, accountId } : { code });
            if (result.status === 0) return show(card, card.dataset.labelUnavailable, true); // not sent: the code is still usable
            if (ok(result.status)) {
                closeLogin(card);
                show(card, "", false);
                window.mdui?.snackbar?.({ message: card.dataset.labelLoginDone });
                await CatapultAdminTabs.patch(panel);
                return;
            }
            if (field) field.value = ""; // a refused code is used up or wrong: the admin pastes a new one
            show(card, refusal(card, result), true);
        } finally {
            button.disabled = false;
        }
    }

    function saveDetails(panel, card, button) {
        const box = button.closest(".admin-epic-details");
        const order = Number(box?.querySelector('[name="fillOrder"]')?.value);
        if (!Number.isInteger(order) || order < 0) return show(card, card.dataset.labelInvalid, true);
        return act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/details`,
            { fillOrder: order }, card.dataset.labelUpdated);
    }

    function attach() {
        const panel = panelOf();
        if (!panel || panel.dataset.epicBound) return;
        panel.dataset.epicBound = "true";
        panel.addEventListener("click", event => {
            const target = event.target.closest?.(
                ".admin-epic-add, .admin-epic-reauth, .admin-epic-login-submit, .admin-epic-details-submit, "
                + ".admin-epic-toggle, .admin-epic-reset, .admin-epic-delete");
            if (!target || !panel.contains(target) || target.disabled) return;
            const card = target.closest(".admin-epic");
            if (!card) return;
            const id = encodeURIComponent(target.dataset.id ?? "");
            if (target.matches(".admin-epic-add")) openLogin(card, null);
            else if (target.matches(".admin-epic-reauth")) openLogin(card, target.dataset.id);
            else if (target.matches(".admin-epic-login-submit")) submitLogin(panel, card, target);
            else if (target.matches(".admin-epic-details-submit")) saveDetails(panel, card, target);
            else if (target.matches(".admin-epic-toggle")) act(panel, card, target, `${BASE}/${id}/enabled`,
                { enabled: target.dataset.enabled === "true" }, card.dataset.labelUpdated);
            else if (target.matches(".admin-epic-reset")) act(panel, card, target, `${BASE}/${id}/reset-limit`, {}, card.dataset.labelUpdated);
            else remove(panel, card, target);
        });
    }

    document.addEventListener("catapult:render", attach);
    document.addEventListener("catapult:admin-panel", attach);

    return { attach };
})();
