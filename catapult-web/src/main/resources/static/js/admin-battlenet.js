/**
 * The admin Keys tab's Battle.net bot pool card. Add a bot (label, e-mail, password), send the 2FA code of
 * a bot waiting for one, re-authenticate, enable / disable, lift "full", change the password and delete
 * (after a confirmation) all post to AdminBattleNetController, the button disabled until the list is
 * patched (admin-tabs.js). A refusal is told inline under the card's form by its status: 409 (a login is
 * already running, or none waits for a code), 404 (gone: patched away), 400 (invalid), else unavailable.
 *
 * Passwords are typed in password fields, only sent in the POST body and emptied once sent; nothing
 * ever puts them in a URL or the page. Labels and BattleTags come from the server-rendered rows, never
 * from innerHTML.
 *
 * While a bot is NEW or CODE_REQUIRED (its row's data-auth-state) the tab is patched every 3 s, so its
 * login progress shows without a reload; the polling stops once none is, once the card has left the page
 * and when another admin tab is shown. Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminBattleNet = (function () {
    const BASE = "/admin/battlenet-accounts";
    const POLL_MS = 3000;
    const PENDING = '.admin-battlenet-row[data-auth-state="NEW"], .admin-battlenet-row[data-auth-state="CODE_REQUIRED"]';
    const ok = status => status >= 200 && status < 300;

    let timer = null;

    /** The status of a POST (0 when it couldn't be sent). */
    async function post(url, body) {
        try {
            return (await CatapultCsrf.postJson(url, body)).status;
        } catch {
            return 0;
        }
    }

    const panelOf = () => document.querySelector('mdui-tab-panel[value="keys"]');

    function refusal(card, status) {
        if (status === 409) return card.dataset.labelConflict;
        if (status === 404) return card.dataset.labelUnknown;
        if (status === 400) return card.dataset.labelInvalid;
        return card.dataset.labelUnavailable;
    }

    function show(card, text, error) {
        const message = card.querySelector(".admin-battlenet-message");
        if (!message) return;
        message.textContent = text ?? "";
        message.classList.toggle("admin-battlenet-error", !!error);
        message.hidden = !text;
    }

    /** Posts an action, holding the button disabled until the list is patched; the fields are emptied once sent. */
    async function act(panel, card, button, url, body, doneLabel, fields) {
        button.disabled = true;
        try {
            const status = await post(url, body);
            if (ok(status)) {
                (fields ?? []).forEach(field => { field.value = ""; });
                show(card, "", false);
                window.mdui?.snackbar?.({ message: doneLabel });
                await CatapultAdminTabs.patch(panel);
                return;
            }
            show(card, refusal(card, status), true);
            if (status === 404 || status === 409) await CatapultAdminTabs.patch(panel);
        } finally {
            button.disabled = false;
        }
    }

    async function remove(panel, card, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            const root = panel.querySelector(".admin-keys") ?? card;
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            await act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/delete`, {}, card.dataset.labelDeleted);
        } finally {
            button.disabled = false;
        }
    }

    function add(panel, card, button) {
        const enroll = card.querySelector(".admin-battlenet-enroll");
        const fields = ["label", "email", "password"].map(name => enroll.querySelector(`[name="${name}"]`));
        const [label, email, password] = fields.map(field => (field?.value ?? "").trim());
        if (!label || !email || !(fields[2]?.value ?? "")) return show(card, card.dataset.labelInvalid, true);
        // the password is sent as typed (not trimmed)
        return act(panel, card, button, BASE, { label, email, password: fields[2].value }, card.dataset.labelCreated, fields);
    }

    function sendCode(panel, card, button) {
        const field = button.closest(".admin-battlenet-code")?.querySelector('[name="code"]');
        const code = (field?.value ?? "").trim();
        if (!code) return show(card, card.dataset.labelInvalid, true);
        return act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/code`, { code },
            card.dataset.labelCodeSent, [field]);
    }

    function changePassword(panel, card, button) {
        const field = button.closest(".admin-battlenet-password")?.querySelector('[name="password"]');
        const password = field?.value ?? "";
        if (!password) return show(card, card.dataset.labelInvalid, true);
        return act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/password`, { password },
            card.dataset.labelUpdated, [field]);
    }

    function stopPolling() {
        clearTimeout(timer);
        timer = null;
    }

    /** Patches the tab again in 3 s while a bot is NEW or CODE_REQUIRED; no second timer, none otherwise. */
    function schedule() {
        const panel = panelOf();
        if (timer !== null || !panel || !panel.querySelector(PENDING)) return;
        timer = setTimeout(async () => {
            timer = null;
            const current = panelOf();
            if (!current || !current.querySelector(PENDING)) return;
            try {
                await CatapultAdminTabs.patch(current);
            } finally {
                schedule();
            }
        }, POLL_MS);
    }

    function attach() {
        const panel = panelOf();
        if (!panel) return;
        if (!panel.dataset.battlenetBound) {
            panel.dataset.battlenetBound = "true";
            panel.addEventListener("click", event => {
                const target = event.target.closest?.(
                    ".admin-battlenet-add, .admin-battlenet-code-submit, .admin-battlenet-password-submit, "
                    + ".admin-battlenet-reauth, .admin-battlenet-toggle, .admin-battlenet-reset, .admin-battlenet-delete");
                if (!target || !panel.contains(target) || target.disabled) return;
                const card = target.closest(".admin-battlenet");
                if (!card) return;
                const id = encodeURIComponent(target.dataset.id ?? "");
                if (target.matches(".admin-battlenet-add")) add(panel, card, target);
                else if (target.matches(".admin-battlenet-code-submit")) sendCode(panel, card, target);
                else if (target.matches(".admin-battlenet-password-submit")) changePassword(panel, card, target);
                else if (target.matches(".admin-battlenet-reauth")) act(panel, card, target, `${BASE}/${id}/reauth`, {}, card.dataset.labelReauthed);
                else if (target.matches(".admin-battlenet-toggle")) act(panel, card, target, `${BASE}/${id}/enabled`,
                    { enabled: target.dataset.enabled === "true" }, card.dataset.labelUpdated);
                else if (target.matches(".admin-battlenet-reset")) act(panel, card, target, `${BASE}/${id}/reset-limit`, {}, card.dataset.labelUpdated);
                else remove(panel, card, target);
            });

            // Leaving the Keys tab stops the polling (mdui's inner change events bubble too: only the tabs' own counts).
            const tabs = document.getElementById("admin-tabs");
            tabs?.addEventListener("change", event => {
                if (event.target === tabs && tabs.value !== "keys") stopPolling();
                else if (event.target === tabs) schedule();
            });
        }
        schedule();
    }

    document.addEventListener("catapult:render", attach);
    // each patch of the tab (and its first render) may bring or clear a pending bot
    document.addEventListener("catapult:admin-panel", () => schedule());

    return { attach };
})();
