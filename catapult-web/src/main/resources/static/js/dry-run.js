/**
 * The banner of a member in dry run (fragments/dry-run.html), rendered with the shell, outside the SPA's #app: "Turn
 * off" opens its confirmation; confirming posts the member's way out (MeDryRunController), then closes the dialog and
 * hides the banner. Cancelling only closes the dialog. A failure keeps the banner and is told in the standard snackbar
 * (CatapultChannel.postJson). Bound once, when the page loads or on the first render that finds the banner.
 */
window.CatapultDryRun = (function () {
    function attach() {
        const banner = document.getElementById("dry-run-banner");
        const dialog = document.getElementById("dry-run-dialog");
        if (!banner || !dialog || banner.dataset.bound) return;
        banner.dataset.bound = "1";

        document.getElementById("dry-run-disable")?.addEventListener("click", () => { dialog.open = true; });
        document.getElementById("dry-run-cancel")?.addEventListener("click", () => { dialog.open = false; });

        const confirm = document.getElementById("dry-run-confirm");
        confirm?.addEventListener("click", async () => {
            if (confirm.disabled) return;
            confirm.disabled = true;
            try {
                // Closed first, so a failure's snackbar isn't hidden behind it.
                dialog.open = false;
                const response = await CatapultChannel.postJson("/me/dry-run/disable", {});
                if (response.ok) banner.hidden = true;
            } finally {
                confirm.disabled = false;
            }
        });
    }

    attach();
    document.addEventListener("catapult:render", attach);

    return { attach };
})();
