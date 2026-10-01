/**
 * Generic client-side counterpart to the server's spa:if Thymeleaf attribute
 * (see IfAttributeProcessor): that attribute leaves a data-if="name,..."
 * breadcrumb in the rendered HTML instead of a JS-specific toggle, so the same named flags
 * SSE handlers already receive can drive the same elements without re-deriving each
 * condition by hand. A flag missing from `state` is left alone rather than treated as
 * false, so a partial SSE payload (e.g. SteamTokenSavedEvent only carries `shared`) can't
 * accidentally hide elements that depend on flags it doesn't know about.
 */
window.Visibility = {
    apply(root, state) {
        root.querySelectorAll("[data-if]").forEach(el => {
            const names = el.dataset.if.split(",");
            // `undefined` (not just a missing key) means unknown too: a setter that merges
            // a partial SSE payload via Object.assign creates the key regardless of whether
            // the event carried it, so hasOwnProperty alone would treat that as "known false".
            const allKnown = names.every(n => state[n] !== undefined);
            if (!allKnown) {
                return;
            }
            el.classList.toggle("hidden", !names.every(n => state[n]));
        });
    }
};
