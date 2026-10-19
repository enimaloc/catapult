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
            if (link) navigator.clipboard?.writeText(link)?.catch(() => {});
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
                succeeded = (await CatapultChannel.postJson("/invite/regenerate", {})).ok;
            } finally {
                // A failed request leaves the page as is: the button is free to try again.
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
