/**
 * The Integrations tab's chat command migration card: each line's button puts it on the clipboard.
 * The card is replaced by each render: listeners go on its elements.
 */
window.CatapultCommandMigration = (function () {
    function attach(card) {
        card.querySelectorAll(".command-migration-copy").forEach(button => button.addEventListener("click", () => {
            const copied = navigator.clipboard?.writeText(button.dataset.copy) ?? Promise.reject(new Error("no clipboard"));
            copied.then(() => CatapultChannel.showError(card.dataset.labelCopied))
                .catch(() => CatapultChannel.showError(card.dataset.labelCopyFailed));
        }));
    }

    document.addEventListener("catapult:render", () => {
        const card = document.getElementById("command-migration");
        if (card) attach(card);
    });

    return { attach };
})();
