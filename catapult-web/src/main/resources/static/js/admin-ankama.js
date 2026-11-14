/**
 * The admin Ankama tab's bot pool card. "Add a bot" (and "Re-authenticate", shown only on a REAUTH_REQUIRED bot) starts
 * a login: the authorize address is shown as a link opening in a new tab, and the admin pastes back the address of the
 * page that then fails to load, which finishes the login. A bot waiting for its Shield code has a code field and "Send
 * a new code". Enable / disable, lift "full", the fill order and delete (after a confirmation) all post to
 * AdminAnkamaController, the button disabled until the list is patched (admin-tabs.js).
 *
 * A refusal is told inline under the card: catapult-api's own message when it sent one (a wrong Shield code, a refused
 * login, as Ankama said it), else a text by the status. Those messages, like the authorize address, only ever reach the
 * page through textContent / an https href, never innerHTML.
 *
 * The pasted address holds a one-time code: it is only sent in the POST body, never logged, and the field is emptied once
 * sent. A login is used up by its first attempt on the API's side, whatever the outcome, so the login box closes then:
 * starting again gives a new address. The login box sits outside the list, so patching the tab keeps it.
 * Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminAnkama = (function () {
    const BASE = "/admin/ankama-accounts";
    const ok = status => status >= 200 && status < 300;

    /** The status of a POST (0 when it couldn't be sent) and its JSON answer (null when it has none). */
    async function post(url, body) {
        try {
            const res = await CatapultCsrf.postJson(url, body);
            let data = null;
            try {
                data = await res.json();
            } catch {
                // an empty answer (202, 204)
            }
            return { status: res.status, data };
        } catch {
            return { status: 0, data: null };
        }
    }

    const panelOf = () => document.querySelector('mdui-tab-panel[value="ankama"]');

    function refusal(card, { status, data }) {
        if (typeof data?.error === "string" && data.error) return data.error;
        if (status === 409) return card.dataset.labelConflict;
        if (status === 404) return card.dataset.labelUnknown;
        if (status === 400) return card.dataset.labelInvalid;
        return card.dataset.labelUnavailable;
    }

    function show(card, text, error) {
        const message = card.querySelector(".admin-ankama-message");
        if (!message) return;
        message.textContent = text ?? "";
        message.classList.toggle("admin-ankama-error", !!error);
        message.hidden = !text;
    }

    /** Posts an action, holding the button disabled until the list is patched; the fields are emptied once it went through. */
    async function act(panel, card, button, url, body, doneLabel, fields) {
        button.disabled = true;
        try {
            const result = await post(url, body);
            if (ok(result.status)) {
                (fields ?? []).forEach(field => { field.value = ""; });
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
            const root = panel.querySelector(".admin-ankama-view") ?? card;
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            await act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/delete`, {}, card.dataset.labelDeleted);
        } finally {
            button.disabled = false;
        }
    }

    function closeLogin(card) {
        const box = card.querySelector(".admin-ankama-login");
        if (!box) return;
        delete box.dataset.loginId;
        const field = box.querySelector('[name="redirectUrl"]');
        if (field) field.value = "";
        box.hidden = true;
    }

    /** Starts a login (a new bot, or `accountId`'s re-authentication) and shows its authorize address. */
    async function startLogin(card, button, accountId) {
        button.disabled = true;
        try {
            const result = await post(`${BASE}/login`, accountId ? { accountId } : {});
            const url = result.data?.authorizeUrl;
            // only an https address becomes a link: nothing else is ever put in an href
            if (!ok(result.status) || !result.data?.loginId || typeof url !== "string" || !url.startsWith("https://")) {
                closeLogin(card);
                show(card, refusal(card, ok(result.status) ? { status: 502 } : result), true);
                return;
            }
            const box = card.querySelector(".admin-ankama-login");
            const link = box.querySelector(".admin-ankama-authorize");
            link.setAttribute("href", url);
            link.textContent = url;
            box.dataset.loginId = result.data.loginId;
            box.hidden = false;
            show(card, "", false);
        } finally {
            button.disabled = false;
        }
    }

    async function completeLogin(panel, card, button) {
        const box = button.closest(".admin-ankama-login");
        const field = box?.querySelector('[name="redirectUrl"]');
        const redirectUrl = (field?.value ?? "").trim();
        const loginId = box?.dataset.loginId;
        if (!redirectUrl || !loginId) return show(card, card.dataset.labelInvalid, true);
        button.disabled = true;
        try {
            const result = await post(`${BASE}/login/${encodeURIComponent(loginId)}/complete`, { redirectUrl });
            if (result.status === 0) return show(card, card.dataset.labelUnavailable, true); // not sent: the login is still usable
            closeLogin(card); // used up on the API's side, whatever the outcome
            if (ok(result.status)) {
                show(card, "", false);
                window.mdui?.snackbar?.({ message: card.dataset.labelLoginDone });
                await CatapultAdminTabs.patch(panel);
                return;
            }
            show(card, refusal(card, result), true);
        } finally {
            button.disabled = false;
        }
    }

    function sendCode(panel, card, button) {
        const field = button.closest(".admin-ankama-code")?.querySelector('[name="code"]');
        const code = (field?.value ?? "").trim();
        if (!code) return show(card, card.dataset.labelInvalid, true);
        return act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/code`, { code },
            card.dataset.labelCodeSent, [field]);
    }

    function saveDetails(panel, card, button) {
        const box = button.closest(".admin-ankama-details");
        const order = Number(box?.querySelector('[name="fillOrder"]')?.value);
        if (!Number.isInteger(order) || order < 0) return show(card, card.dataset.labelInvalid, true);
        return act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/details`,
            { fillOrder: order }, card.dataset.labelUpdated);
    }

    function attach() {
        const panel = panelOf();
        if (!panel || panel.dataset.ankamaBound) return;
        panel.dataset.ankamaBound = "true";
        panel.addEventListener("click", event => {
            const target = event.target.closest?.(
                ".admin-ankama-add, .admin-ankama-reauth, .admin-ankama-login-submit, .admin-ankama-code-submit, "
                + ".admin-ankama-code-resend, .admin-ankama-details-submit, .admin-ankama-toggle, .admin-ankama-reset, "
                + ".admin-ankama-delete");
            if (!target || !panel.contains(target) || target.disabled) return;
            const card = target.closest(".admin-ankama");
            if (!card) return;
            const id = encodeURIComponent(target.dataset.id ?? "");
            if (target.matches(".admin-ankama-add")) startLogin(card, target, null);
            else if (target.matches(".admin-ankama-reauth")) startLogin(card, target, target.dataset.id);
            else if (target.matches(".admin-ankama-login-submit")) completeLogin(panel, card, target);
            else if (target.matches(".admin-ankama-code-submit")) sendCode(panel, card, target);
            else if (target.matches(".admin-ankama-code-resend")) act(panel, card, target, `${BASE}/${id}/code/resend`, {}, card.dataset.labelCodeResent);
            else if (target.matches(".admin-ankama-details-submit")) saveDetails(panel, card, target);
            else if (target.matches(".admin-ankama-toggle")) act(panel, card, target, `${BASE}/${id}/enabled`,
                { enabled: target.dataset.enabled === "true" }, card.dataset.labelUpdated);
            else if (target.matches(".admin-ankama-reset")) act(panel, card, target, `${BASE}/${id}/reset-limit`, {}, card.dataset.labelUpdated);
            else remove(panel, card, target);
        });
    }

    document.addEventListener("catapult:render", attach);
    document.addEventListener("catapult:admin-panel", attach);

    return { attach };
})();
