/**
 * Generic client-side counterpart to the server's sp:visible-when Thymeleaf attribute
 * (see VisibleWhenAttributeProcessor): that attribute leaves a data-visible-when="name,..."
 * breadcrumb in the rendered HTML instead of a JS-specific toggle, so the same named flags
 * SSE handlers already receive can drive the same elements without re-deriving each
 * condition by hand. A flag missing from `state` is left alone rather than treated as
 * false, so a partial SSE payload (e.g. SteamTokenSavedEvent only carries `shared`) can't
 * accidentally hide elements that depend on flags it doesn't know about.
 */
window.Visibility = {
    apply(root, state) {
        root.querySelectorAll("[data-visible-when]").forEach(el => {
            const names = el.dataset.visibleWhen.split(",");
            const allKnown = names.every(n => Object.prototype.hasOwnProperty.call(state, n));
            if (!allKnown) {
                return;
            }
            el.classList.toggle("hidden", !names.every(n => state[n]));
        });
    }
};
