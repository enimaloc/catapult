/**
 * The admin Keys tab's Minecraft service accounts card. Enable / disable, reset the friend limit and
 * delete (after a confirmation) post to AdminMinecraftController with the button disabled until the
 * tab has reloaded; a refusal is told by its status: 409 (users are linked to it), 404 (gone: reloads),
 * else unavailable.
 *
 * "Add an account" and "Re-authenticate" run catapult-api's Microsoft device-code flow: get a device
 * code, show its user code and verification link, then poll the create / reauth route every
 * max(3, interval) s: 202 → poll again, 200 → done (the tab reloads), anything else → the error, stop.
 * A refusal (of the device code or of a poll) shows its localized label then catapult-api's message.
 * A verification link that isn't https:// is never rendered as a link and no polling starts. Polling
 * also stops once the flow's element has left the page (the panel's content was replaced) or another
 * admin tab is shown. Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminMinecraft = (function () {
    const BASE = "/admin/minecraft-accounts";
    const MIN_INTERVAL_S = 3;
    const DEFAULT_INTERVAL_S = 5;
    const ok = status => status >= 200 && status < 300;

    /** The flows polling right now, each by the function that cancels it. */
    const flows = new Set();

    /** The answer of a POST (status 0 when it couldn't be sent) and its JSON body (null when none). */
    async function post(url, body) {
        try {
            const res = await CatapultCsrf.postJson(url, body);
            let json = null;
            try {
                json = await res.json();
            } catch {
                // an empty or non-JSON body: the status is what counts
            }
            return { status: res.status, body: json };
        } catch {
            return { status: 0, body: null };
        }
    }

    function refusal(card, status) {
        if (status === 409) return card.dataset.labelLinked;
        if (status === 404) return card.dataset.labelUnknown;
        return card.dataset.labelUnavailable;
    }

    /** Posts an account action, holding the button disabled until the tab has reloaded. */
    async function act(panel, card, button, url, body) {
        button.disabled = true;
        try {
            const { status } = await post(url, body);
            if (ok(status)) {
                window.mdui?.snackbar?.({ message: url.endsWith("/delete") ? card.dataset.labelDeleted : card.dataset.labelUpdated });
                await CatapultAdminTabs.reload(panel);
                return;
            }
            CatapultChannel.showError(refusal(card, status));
            if (status === 404) await CatapultAdminTabs.reload(panel);
        } finally {
            button.disabled = false;
        }
    }

    async function remove(panel, card, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            const root = panel.querySelector(".admin-keys") ?? card;
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            await act(panel, card, button, `${BASE}/${encodeURIComponent(button.dataset.id)}/delete`, {});
        } finally {
            button.disabled = false;
        }
    }

    /** Only an absolute https:// link is shown, and only then does the flow go on. */
    function secure(uri) {
        if (typeof uri !== "string" || !uri.startsWith("https://")) return false;
        try {
            return new URL(uri).protocol === "https:";
        } catch {
            return false;
        }
    }

    function showText(flowEl, text, error) {
        const p = document.createElement("p");
        p.className = error ? "admin-minecraft-error" : "admin-cell-muted";
        p.textContent = text;
        flowEl.replaceChildren(p);
    }

    /** The label for a failed poll's status. */
    function pollFailure(card, status) {
        if (status === 400) return card.dataset.labelExpired;
        if (status === 404) return card.dataset.labelUnknown;
        return card.dataset.labelFailed;
    }

    /** The flow's error: its label, then catapult-api's message (e.g. Microsoft's AADSTS refusal) when it gave one. */
    function showFailure(flowEl, label, message) {
        showText(flowEl, typeof message === "string" && message.trim() ? `${label} (${message.trim()})` : label, true);
    }

    function showCode(card, flowEl, code) {
        const line = document.createElement("p");
        line.className = "admin-minecraft-code";
        const userCode = document.createElement("strong");
        userCode.className = "admin-minecraft-user-code";
        userCode.textContent = code.userCode ?? "";
        const link = document.createElement("a");
        link.href = code.verificationUri;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = code.verificationUri;
        line.append(`${card.dataset.labelCode} `, userCode, " — ", link);
        const pending = document.createElement("p");
        pending.className = "admin-cell-muted";
        pending.textContent = card.dataset.labelPending;
        flowEl.replaceChildren(line, pending);
    }

    /**
     * Runs a device-code flow shown in `flowEl`, polling `pollUrl` with `body` + the device code;
     * `button` stays disabled until it ends.
     */
    async function runFlow(panel, card, button, flowEl, pollUrl, body, doneLabel) {
        let timer = null;
        let over = false;
        const end = () => {
            over = true;
            clearTimeout(timer);
            flows.delete(cancel);
            button.disabled = false;
        };
        const cancel = () => {
            end();
            flowEl.replaceChildren();
        };
        // the panel's content was replaced (a reload, another view) or the page was left
        const gone = () => !flowEl.isConnected || !panel.contains(flowEl);

        button.disabled = true;
        flows.add(cancel);
        showText(flowEl, "…");
        const { status, body: code } = await post(`${BASE}/device-code`, {});
        if (over) return;
        if (gone()) return end();
        if (status !== 200 || !code?.deviceCode) {
            showFailure(flowEl, card.dataset.labelFailed, code?.message);
            return end();
        }
        if (!secure(code.verificationUri)) {
            showText(flowEl, card.dataset.labelInsecure, true);
            return end();
        }
        showCode(card, flowEl, code);
        const intervalMs = Math.max(MIN_INTERVAL_S, Number(code.interval) || DEFAULT_INTERVAL_S) * 1000;

        const tick = async () => {
            if (over) return;
            if (gone()) return end();
            const answer = await post(pollUrl, { ...body, deviceCode: code.deviceCode });
            if (over) return;
            if (gone()) return end();
            if (answer.status === 202) {
                timer = setTimeout(tick, intervalMs);
                return;
            }
            if (answer.status === 200) {
                end();
                button.disabled = true; // until the tab has reloaded
                window.mdui?.snackbar?.({ message: doneLabel });
                try {
                    await CatapultAdminTabs.reload(panel);
                } finally {
                    button.disabled = false;
                }
                return;
            }
            showFailure(flowEl, pollFailure(card, answer.status), answer.body?.message);
            end();
        };
        timer = setTimeout(tick, intervalMs);
    }

    function add(panel, card, button) {
        const field = card.querySelector('.admin-minecraft-enroll [name="label"]');
        const flowEl = card.querySelector(".admin-minecraft-enroll .admin-minecraft-flow");
        if (!flowEl) return;
        const label = (field?.value ?? "").trim();
        return runFlow(panel, card, button, flowEl, `${BASE}/create`, label ? { label } : {}, card.dataset.labelCreated);
    }

    function reauth(panel, card, button) {
        const flowEl = button.closest(".admin-minecraft-row")?.querySelector(".admin-minecraft-flow");
        if (!flowEl) return;
        return runFlow(panel, card, button, flowEl, `${BASE}/${encodeURIComponent(button.dataset.id)}/reauth`, {},
            card.dataset.labelReauthed);
    }

    function cancelAll() {
        [...flows].forEach(cancel => cancel());
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="keys"]');
        if (!panel) return;

        panel.addEventListener("click", event => {
            const target = event.target.closest?.(
                ".admin-minecraft-add, .admin-minecraft-reauth, .admin-minecraft-toggle, .admin-minecraft-reset, .admin-minecraft-delete");
            if (!target || !panel.contains(target) || target.disabled) return;
            const card = target.closest(".admin-minecraft");
            if (!card) return;
            const id = encodeURIComponent(target.dataset.id ?? "");
            if (target.matches(".admin-minecraft-add")) add(panel, card, target);
            else if (target.matches(".admin-minecraft-reauth")) reauth(panel, card, target);
            else if (target.matches(".admin-minecraft-toggle")) act(panel, card, target, `${BASE}/${id}/enabled`, { enabled: target.dataset.enabled === "true" });
            else if (target.matches(".admin-minecraft-reset")) act(panel, card, target, `${BASE}/${id}/reset-limit`, {});
            else remove(panel, card, target);
        });

        // Leaving the Keys tab ends its flows (mdui's inner change events bubble too: only the tabs' own counts).
        const tabs = document.getElementById("admin-tabs");
        tabs?.addEventListener("change", event => {
            if (event.target === tabs && tabs.value !== "keys") cancelAll();
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
