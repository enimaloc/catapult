/**
 * The Settings tab's danger zone (owners only). "Delete my account" opens a dialog emptied each
 * time; its confirm button stays disabled until the typed name equals the channel's, ignoring case
 * as catapult-api compares it (which answers 204 and does nothing on a mismatch, so this check is
 * all that keeps a typo from looking like a success). Confirm and "Cancel the deletion" post once
 * (the button is disabled while the request runs), then re-render the channel page, which shows
 * the card's new state; a refusal or an outage is shown as a localized message (data-label-* on the
 * card). The card is replaced by each render: listeners go on its elements.
 */
window.CatapultDangerZone = (function () {
    async function act(card, button, url, body) {
        if (button.disabled) return;
        button.disabled = true;
        let succeeded = false;
        try {
            const response = await CatapultCsrf.postJson(url, body);
            succeeded = response.ok;
            if (!succeeded) {
                CatapultChannel.showError(response.status === 401 || response.status === 403
                    ? card.dataset.labelRefused : card.dataset.labelUnavailable);
            }
        } catch (e) {
            CatapultChannel.showError(card.dataset.labelUnavailable);
        } finally {
            // A failed request leaves the page as is: the button is free to try again.
            if (!succeeded) button.disabled = false;
        }
        if (succeeded) await CatapultChannel.refresh();
    }

    function attach(card) {
        const baseUrl = CatapultChannel.baseUrl();

        const cancel = card.querySelector("#danger-zone-cancel-btn");
        cancel?.addEventListener("click", () => act(card, cancel, `${baseUrl}/settings/cancel-deletion`, {}));

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
        confirm.addEventListener("click", () => {
            if (!matches()) return;
            act(card, confirm, `${baseUrl}/settings/delete-account`, { confirmUsername: field.value });
        });
    }

    document.addEventListener("catapult:render", () => {
        const card = document.getElementById("channel-danger-zone");
        if (card) attach(card);
    });

    return { attach };
})();
