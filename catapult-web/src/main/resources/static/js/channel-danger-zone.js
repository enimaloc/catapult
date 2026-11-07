/**
 * The Settings tab's danger zone (owners only). "Delete my account" opens a dialog emptied each
 * time; its confirm button stays disabled until the typed name equals the channel's, ignoring case
 * as catapult-api compares it (which refuses a mismatch with a 400, shown as unavailable).
 * Confirm and "Cancel the deletion" post once (the button is disabled while the request runs), then
 * show the card's other state in place: both are rendered, the one not shown hidden. A new request's
 * date is the card's data-delay-days from now, in the page's language as the server writes it (its
 * countdown kept current by time.js). A refusal or an outage is shown as a localized message
 * (data-label-* on the card). The card is replaced by each render: listeners go on its elements.
 */
window.CatapultDangerZone = (function () {
    const DAY_MS = 86_400_000;

    /** Posts, and answers whether it went through (a failure told). */
    async function post(card, button, url, body) {
        if (button.disabled) return false;
        button.disabled = true;
        try {
            const response = await CatapultCsrf.postJson(url, body);
            if (!response.ok) {
                CatapultChannel.showError(response.status === 401 || response.status === 403
                    ? card.dataset.labelRefused : card.dataset.labelUnavailable);
            }
            return response.ok;
        } catch (e) {
            CatapultChannel.showError(card.dataset.labelUnavailable);
            return false;
        } finally {
            button.disabled = false;
        }
    }

    /** Shows the card pending its deletion (at `deletesAt`, a Date) or not (null). */
    function showPending(card, deletesAt) {
        const pending = !!deletesAt;
        const show = (selector, visible) => card.querySelector(selector)?.classList.toggle("hidden", !visible);
        show(".danger-zone-title", !pending);
        show(".danger-zone-pending-title", pending);
        show("#danger-zone-pending", pending);
        show("#danger-zone-request", !pending);
        if (!pending) return;
        const on = card.querySelector(".danger-zone-deletes-on");
        const date = new Intl.DateTimeFormat(document.documentElement.lang || undefined,
            { day: "2-digit", month: "2-digit", year: "numeric" }).format(deletesAt);
        if (on) on.textContent = (on.dataset.label ?? "").replace("{0}", () => date);
        card.querySelector("#danger-zone-pending [data-live-time]")?.setAttribute("datetime", deletesAt.toISOString());
        show("#danger-zone-deletes", true);
        window.CatapultTime?.refresh?.(card);
    }

    function attach(card) {
        const baseUrl = CatapultChannel.baseUrl();

        const cancel = card.querySelector("#danger-zone-cancel-btn");
        cancel?.addEventListener("click", async () => {
            if (await post(card, cancel, `${baseUrl}/settings/cancel-deletion`, {})) showPending(card, null);
        });

        const dialog = card.querySelector("#danger-zone-delete-dialog");
        if (!dialog) return;
        const field = dialog.querySelector('[name="confirmUsername"]');
        const confirm = dialog.querySelector(".danger-zone-confirm");
        const matches = () => (field.value ?? "").toLowerCase() === (card.dataset.username ?? "").toLowerCase();
        const sync = () => { confirm.disabled = !matches(); };

        card.querySelector("#danger-zone-delete-btn")?.addEventListener("click", () => {
            field.value = "";
            sync();
            dialog.open = true;
        });
        dialog.querySelector(".danger-zone-dismiss")?.addEventListener("click", () => { dialog.open = false; });
        field.addEventListener("input", sync);
        sync();
        confirm.addEventListener("click", async () => {
            if (!matches()) return;
            const sent = await post(card, confirm, `${baseUrl}/settings/delete-account`, { confirmUsername: field.value });
            sync();
            if (!sent) return;
            dialog.open = false;
            showPending(card, new Date(Date.now() + Number(card.dataset.delayDays ?? 0) * DAY_MS));
        });
    }

    document.addEventListener("catapult:render", () => {
        const card = document.getElementById("channel-danger-zone");
        if (card) attach(card);
    });

    return { attach };
})();
