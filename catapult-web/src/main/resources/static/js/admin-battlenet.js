/**
 * The admin Keys tab's Battle.net bot pool card. Add a bot (e-mail, password), send the 2FA code of
 * a bot waiting for one, re-authenticate, enable / disable, lift "full", change the password and delete
 * (after a confirmation) all post to AdminBattleNetController, the button disabled until the list is
 * patched (admin-tabs.js). A refusal is told inline under the card's form by its status: 409 (a login is
 * already running, or none waits for a code), 404 (gone: patched away), 400 (invalid), else unavailable.
 *
 * Passwords are typed in password fields, only sent in the POST body and emptied once sent; nothing
 * ever puts them in a URL or the page. Display names (BattleTag, else masked e-mail) come from the
 * server-rendered rows, never from innerHTML.
 *
 * While a bot is NEW, or CODE_REQUIRED with a login waiting for its code (its row's data-auth-state and
 * data-waiting-code), the tab is patched every 3 s, so its login progress shows without a reload; the
 * polling stops once none is, once the card has left the page and when another admin tab is shown. Listeners are delegated to the panel: its content comes and goes.
 *
 * A READY bot's "BGS call" panel sends a free BGS call: a known method (service and method from bgs-methods,
 * loaded once when a panel first opens, body in protobuf text) or raw (hash, method id, hex body). It shows the
 * BGS status, the reply and the pushes of the 5 s after, filtered client side; all of it through textContent.
 */
window.CatapultAdminBattleNet = (function () {
    const BASE = "/admin/battlenet-accounts";
    const POLL_MS = 3000;
    const PENDING = '.admin-battlenet-row[data-auth-state="NEW"], '
        + '.admin-battlenet-row[data-auth-state="CODE_REQUIRED"][data-waiting-code="true"]';
    const ok = status => status >= 200 && status < 300;

    let timer = null;
    let catalog = null;

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
        const fields = ["email", "password"].map(name => enroll.querySelector(`[name="${name}"]`));
        const email = (fields[0]?.value ?? "").trim();
        const password = fields[1]?.value ?? "";
        if (!email || !password) return show(card, card.dataset.labelInvalid, true);
        // the password is sent as typed (not trimmed)
        return act(panel, card, button, BASE, { email, password }, card.dataset.labelCreated, fields);
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

    // --- the free BGS call ---

    /** bgs-methods, read once per page (null again after a failure, so the next opening retries). */
    function methods() {
        catalog ??= fetch(`${BASE}/bgs-methods`, { credentials: "same-origin" })
            .then(response => response.ok ? response.json() : Promise.reject(response.status))
            .catch(error => { catalog = null; throw error; });
        return catalog;
    }

    // Native selects: mdui-select resets a value set from code while its menu takes new items
    const option = (value, text) => new Option(text, value);

    function fillMethods(box, services) {
        const service = services.find(s => s.name === box.querySelector('[name="bgsService"]').value);
        box.querySelector('[name="bgsMethod"]').replaceChildren(...(service?.methods ?? []).map(m =>
            option(m.name, `${m.name} (${m.id})${m.noResponse ? " · NO_RESPONSE" : ""}`)));
    }

    /** Fills the lists while they are empty: on the first opening, and again after a tab patch emptied them. */
    async function openBgs(card, box) {
        const select = box.querySelector('[name="bgsService"]');
        if (select.options.length) return;
        try {
            const services = await methods();
            select.replaceChildren(...services.map(s => option(s.name, `${s.name} · ${s.hash}`)));
            fillMethods(box, services);
        } catch (status) {
            show(card, refusal(card, status), true);
        }
    }

    function bgsMode(box) {
        const raw = box.querySelector('[name="bgsMode"]').value === "raw";
        box.querySelector(".admin-battlenet-bgs-known").hidden = raw;
        box.querySelector(".admin-battlenet-bgs-raw").hidden = !raw;
    }

    function bgsRequest(box) {
        const field = name => box.querySelector(`[name="${name}"]`);
        if (field("bgsMode").value !== "raw") {
            return { service: field("bgsService").value, method: field("bgsMethod").value, text: field("bgsText").value };
        }
        const methodId = Number(field("bgsMethodId").value);
        return {
            serviceHash: field("bgsHash").value.trim(),
            methodId: Number.isInteger(methodId) && field("bgsMethodId").value !== "" ? methodId : null,
            hex: field("bgsHex").value,
            withCiid: field("bgsCiid").checked
        };
    }

    function line(tag, text, className) {
        const node = document.createElement(tag);
        node.textContent = text;
        if (className) node.className = className;
        return node;
    }

    function showResult(box, labels, result) {
        const status = result.status === null || result.status === undefined ? labels.noResponse
            : `status ${result.status}${result.status === 0 ? " (OK)" : ""}`;
        box.querySelector(".admin-battlenet-bgs-status").textContent =
            `${status} · ${result.durationMs} ms${result.error ? ` · ${result.error}` : ""}`;
        box.querySelector(".admin-battlenet-bgs-reply").textContent = result.replyText ?? result.replyHex ?? "";
        const pushes = result.pushes ?? [];
        box.querySelector(".admin-battlenet-bgs-pushes").replaceChildren(
            line("li", pushes.length ? labels.pushes : labels.noPush, "admin-cell-muted admin-battlenet-bgs-pushes-head"),
            ...pushes.map(p => {
                const item = line("li", `${p.service}.${p.method}`);
                item.append(line("pre", p.text ?? p.hex));
                return item;
            }));
        box.querySelector('[name="bgsFilter"]').value = "";
        box.querySelector(".admin-battlenet-bgs-result").hidden = false;
    }

    function filterPushes(box) {
        const needle = box.querySelector('[name="bgsFilter"]').value.trim().toLowerCase();
        box.querySelectorAll(".admin-battlenet-bgs-pushes > li:not(.admin-battlenet-bgs-pushes-head)").forEach(item => {
            item.hidden = !!needle && !item.textContent.toLowerCase().includes(needle);
        });
    }

    /** Sends the call; the button stays disabled (and says so) for the ~5 s the API waits for the pushes. */
    async function sendBgs(card, button) {
        const box = button.closest(".admin-battlenet-bgs");
        const label = button.textContent;
        button.disabled = true;
        button.textContent = button.dataset.labelSending;
        try {
            const response = await CatapultCsrf.postJson(`${BASE}/${encodeURIComponent(button.dataset.id)}/bgs-call`,
                bgsRequest(box));
            const body = await response.json().catch(() => null);
            if (!response.ok) {
                show(card, body?.error ?? refusal(card, response.status), true);
                return;
            }
            show(card, "", false);
            showResult(box, {
                noResponse: button.dataset.labelNoResponse, pushes: button.dataset.labelPushes,
                noPush: button.dataset.labelNoPush
            }, body);
        } catch {
            show(card, card.dataset.labelUnavailable, true);
        } finally {
            button.textContent = label;
            button.disabled = false;
        }
    }

    /** Whether the Keys tab is the one shown (an unmounted tabs element counts as shown: nothing to compare). */
    function keysShown() {
        const tabs = document.getElementById("admin-tabs");
        return !tabs || (tabs.value ?? tabs.getAttribute("value")) === "keys";
    }

    function saveDetails(panel, card, button) {
        const box = button.closest(".admin-battlenet-details");
        const order = Number(box?.querySelector('[name="fillOrder"]')?.value);
        if (!Number.isInteger(order) || order < 0) return show(card, card.dataset.labelInvalid, true);
        return act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/details`,
            { fillOrder: order }, card.dataset.labelUpdated);
    }

    function stopPolling() {
        clearTimeout(timer);
        timer = null;
    }

    /** Patches the tab again in 3 s while a bot is NEW or waits for its code; no second timer, none otherwise. */
    function schedule() {
        const panel = panelOf();
        if (timer !== null || !panel || !keysShown() || !panel.querySelector(PENDING)) return;
        timer = setTimeout(async () => {
            timer = null;
            const current = panelOf();
            if (!current || !keysShown() || !current.querySelector(PENDING)) return;
            try {
                await CatapultAdminTabs.patch(current);
            } finally {
                schedule(); // a no-op once the tab was left meanwhile
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
                    ".admin-battlenet-add, .admin-battlenet-code-submit, .admin-battlenet-password-submit, .admin-battlenet-details-submit, "
                    + ".admin-battlenet-reauth, .admin-battlenet-toggle, .admin-battlenet-reset, .admin-battlenet-delete, "
                    + ".admin-battlenet-bgs-send");
                if (!target || !panel.contains(target) || target.disabled) return;
                const card = target.closest(".admin-battlenet");
                if (!card) return;
                const id = encodeURIComponent(target.dataset.id ?? "");
                if (target.matches(".admin-battlenet-bgs-send")) sendBgs(card, target);
                else if (target.matches(".admin-battlenet-add")) add(panel, card, target);
                else if (target.matches(".admin-battlenet-code-submit")) sendCode(panel, card, target);
                else if (target.matches(".admin-battlenet-password-submit")) changePassword(panel, card, target);
                else if (target.matches(".admin-battlenet-details-submit")) saveDetails(panel, card, target);
                else if (target.matches(".admin-battlenet-reauth")) act(panel, card, target, `${BASE}/${id}/reauth`, {}, card.dataset.labelReauthed);
                else if (target.matches(".admin-battlenet-toggle")) act(panel, card, target, `${BASE}/${id}/enabled`,
                    { enabled: target.dataset.enabled === "true" }, card.dataset.labelUpdated);
                else if (target.matches(".admin-battlenet-reset")) act(panel, card, target, `${BASE}/${id}/reset-limit`, {}, card.dataset.labelUpdated);
                else remove(panel, card, target);
            });

            // toggle does not bubble: caught on its way down
            panel.addEventListener("toggle", event => {
                const box = event.target;
                if (!box.matches?.(".admin-battlenet-bgs") || !box.open) return;
                const card = box.closest(".admin-battlenet");
                if (card) openBgs(card, box);
            }, true);
            panel.addEventListener("change", event => {
                const box = event.target.closest?.(".admin-battlenet-bgs");
                if (box && event.target.matches('[name="bgsMode"]')) bgsMode(box);
                if (box && event.target.matches('[name="bgsService"]')) methods().then(services => fillMethods(box, services));
            });
            panel.addEventListener("input", event => {
                const box = event.target.closest?.(".admin-battlenet-bgs");
                if (box && event.target.matches('[name="bgsFilter"]')) filterPushes(box);
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
