/**
 * Title fields' previews ([data-title-preview], right after their field): the title the field's text would send now,
 * rendered by catapult-api with what it is the title of (data-preview-kind, data-binding-id, data-custom-binding-id).
 * Asked at once (a data-preview-lazy one, as each binding row's, only once its field is first used), then once typing
 * pauses (400 ms); a title that wouldn't be sent shows data-none. A failed preview
 * keeps the last one shown, quietly: it is a hint, not an action.
 */
window.CatapultTitlePreview = (function () {
    const DELAY_MS = 400;
    const ATTACHED = Symbol.for("catapult.titlePreview");

    function attach(preview) {
        if (preview[ATTACHED]) return;
        const field = preview.previousElementSibling;
        if (!field) return;
        preview[ATTACHED] = true;
        const shown = preview.querySelector(".title-preview-text");
        let timer = null;
        let asked = 0;

        async function ask() {
            const mine = ++asked;
            let answer;
            try {
                answer = await CatapultCsrf.postJson(`${CatapultChannel.baseUrl()}/title/preview`, {
                    template: field.value ?? "",
                    kind: preview.dataset.previewKind,
                    bindingId: preview.dataset.bindingId || null,
                    customBindingId: preview.dataset.customBindingId || null,
                });
            } catch {
                return;
            }
            // A newer text was asked since: its answer is the one to show
            if (mine !== asked || !answer?.ok) return;
            const { title } = await answer.json();
            shown.textContent = title ?? preview.dataset.none ?? "";
        }

        field.addEventListener("input", () => {
            clearTimeout(timer);
            timer = setTimeout(ask, DELAY_MS);
        });
        // A dashboard lists dozens of bindings, their panels closed: theirs are asked for once used
        if ("previewLazy" in preview.dataset) field.addEventListener("focus", ask, { once: true });
        else ask();
    }

    function scan(root) {
        if (root.matches?.("[data-title-preview]")) attach(root);
        root.querySelectorAll?.("[data-title-preview]").forEach(attach);
    }

    // With a page, a tab built once picked (lazy.js) or a row a live event adds: all are added nodes.
    new MutationObserver(records => records.forEach(record => record.addedNodes.forEach(node => {
        if (node.nodeType === Node.ELEMENT_NODE) scan(node);
    }))).observe(document.documentElement, { childList: true, subtree: true });
    scan(document);

    return { scan };
})();
