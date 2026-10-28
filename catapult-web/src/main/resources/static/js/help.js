/**
 * Contextual help: a card's [data-help] button loads /spa/help/{cardId} into the page's one side
 * sheet (#help-panel, a modal navigation drawer that closes on Escape and on the overlay by itself)
 * and opens it. Clicks are delegated, so the cards a render replaces need no re-binding. Each click
 * supersedes the earlier ones: only the latest answer is shown, however the requests are ordered.
 */
window.CatapultHelp = (function () {
    let latest = 0;

    async function open(cardId) {
        const panel = document.getElementById("help-panel");
        const content = document.getElementById("help-content");
        if (!panel || !content) return;
        const mine = ++latest;
        let html;
        try {
            const response = await fetch(`/spa/help/${encodeURIComponent(cardId)}`, { headers: { Accept: "text/html" } });
            if (!response.ok) return;
            html = await response.text();
        } catch (e) {
            return;
        }
        if (mine !== latest) return;
        content.innerHTML = html;
        panel.open = true;
    }

    document.addEventListener("click", event => {
        const target = event.target.closest ? event.target : event.target.parentElement;
        const button = target?.closest("[data-help]");
        if (button) return open(button.dataset.help);
        if (target?.closest("[data-help-close]")) {
            const panel = document.getElementById("help-panel");
            if (panel) panel.open = false;
        }
    });

    return { open };
})();
