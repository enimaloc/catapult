/**
 * The Integrations tab's chat command migration card: the menu shows the steps of the tool picked
 * (the other panels get the `hidden` class), and each line's button puts it on the clipboard. The
 * card is replaced by each render: listeners go on its elements.
 */
window.CatapultCommandMigration = (function () {
    function show(card, tool) {
        card.querySelectorAll(".command-migration-panel").forEach(panel =>
            panel.classList.toggle("hidden", panel.dataset.tool !== tool));
    }

    function attach(card) {
        const menu = card.querySelector("#command-migration-tool");
        menu?.addEventListener("change", () => {
            // An mdui-select re-picking its own value clears it: keep the panel shown then.
            if (menu.value) show(card, menu.value);
        });
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
