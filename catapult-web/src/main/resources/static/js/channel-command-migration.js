/**
 * The Commands tab's chat command migration card: the menu shows the steps of the tool picked
 * (the other panels get the `hidden` class), and each line's button puts it on the clipboard. The
 * card is replaced by each render: listeners go on its elements.
 * Its links read the summary with the read-only token's placeholder (data-summary-url, rendered as
 * data-summary-placeholder-url): a read-only token generated on the page (api-token-cards.js's
 * `catapult:api-token` event) takes its place, the placeholder coming back once it is revoked.
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

    /** Puts `summaryUrl` in place of the current one in the card's links (each field and its copy button). */
    function replaceSummaryUrl(card, summaryUrl) {
        const old = card.dataset.summaryUrl;
        if (!old || !summaryUrl || old === summaryUrl) return;
        card.querySelectorAll("mdui-text-field").forEach(field => {
            const value = field.value ?? field.getAttribute("value") ?? "";
            if (value.includes(old)) field.value = value.replaceAll(old, summaryUrl);
        });
        card.querySelectorAll("[data-copy]").forEach(button => {
            if (button.dataset.copy.includes(old)) button.dataset.copy = button.dataset.copy.replaceAll(old, summaryUrl);
        });
        card.dataset.summaryUrl = summaryUrl;
    }

    document.addEventListener("catapult:api-token", event => {
        const card = document.getElementById("command-migration");
        if (!card || event.detail?.base !== "/me/api-token/read") return;
        replaceSummaryUrl(card, event.detail.created?.summaryUrl ?? card.dataset.summaryPlaceholderUrl);
    });

    document.addEventListener("catapult:render", () => {
        const card = document.getElementById("command-migration");
        if (card) attach(card);
    });

    return { attach };
})();
