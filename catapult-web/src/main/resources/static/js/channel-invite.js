/**
 * The Invitations tab: "Copy the link" puts the invite link on the clipboard; "Regenerate the code"
 * opens a confirmation, whose confirm posts once (the button is disabled while the request runs), then
 * re-renders the channel page, which shows the new code. The card is replaced by each render:
 * listeners go on its elements.
 */
window.CatapultInvite = (function () {
    function attach(card) {
        card.querySelector("#invite-copy-btn")?.addEventListener("click", () => {
            const field = card.querySelector("#invite-link");
            const link = field?.value || field?.getAttribute("value");
            if (!link) return;
            const copied = navigator.clipboard?.writeText(link) ?? Promise.reject(new Error("no clipboard"));
            copied.then(() => CatapultChannel.showError(card.dataset.labelCopied))
                .catch(() => CatapultChannel.showError(card.dataset.labelCopyFailed));
        });

        const dialog = card.querySelector("#invite-regenerate-dialog");
        card.querySelector("#invite-regenerate-btn")?.addEventListener("click", () => { dialog.open = true; });
        dialog.querySelector(".invite-regenerate-dismiss")?.addEventListener("click", () => { dialog.open = false; });

        const confirm = dialog.querySelector(".invite-regenerate-confirm");
        confirm.addEventListener("click", async () => {
            if (confirm.disabled) return;
            confirm.disabled = true;
            let succeeded = false;
            try {
                const response = await CatapultCsrf.postJson("/invite/regenerate", {});
                succeeded = response.ok;
                if (!succeeded) {
                    // Closed, so the message isn't hidden behind it; the button is free for the next try.
                    dialog.open = false;
                    CatapultChannel.showError(response.status === 403 ? card.dataset.labelRefused
                        : response.status === 429 ? card.dataset.labelRateLimited : card.dataset.labelUnavailable);
                }
            } catch (e) {
                dialog.open = false;
                CatapultChannel.showError(card.dataset.labelUnavailable);
            } finally {
                if (!succeeded) confirm.disabled = false;
            }
            if (succeeded) await CatapultChannel.refresh();
        });
    }

    document.addEventListener("catapult:render", () => {
        const card = document.getElementById("channel-invite");
        if (card) attach(card);
    });

    return { attach };
})();
