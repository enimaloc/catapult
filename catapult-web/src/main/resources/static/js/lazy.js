/**
 * Parts of a page built only once shown: a <template data-lazy> holds them until then (the channel page's heavy
 * tabs but the one open, the body of each binding and Twitchat event until it is opened). The browser builds no
 * element nor shadow root for them meanwhile, and a password manager has none to walk: Bitwarden walks every element
 * and shadow root of the page again on any change inside one, a second each time on a channel page of 3,000.
 *
 * show(template) puts its content in its place and tells the page's scripts (catapult:lazy, dispatched on its
 * parent, detail.root, and bubbling up to the document), unless `quiet`: during a render, whose own listeners then
 * bind what is there. A collapse item's body, or a select's options, are shown as it opens, here for any.
 *
 * The live events their spa:* rules name are applied to them while they wait (Visibility). One a script applies
 * by hand (channel-events.js's customHandlers: the settings, a connection) can't reach them: touch() marks the
 * tabs waiting then stale, and detail.stale tells the page to catch up on the channel's state once shown. A
 * collapse item's body has no such event (a binding's are rules, a Twitchat event's none), nor is one ever stale.
 */
window.CatapultLazy = (function () {
    const stale = new WeakSet();

    /** The templates under `root` still waiting to be shown. */
    function pending(root = document) {
        return [...root.querySelectorAll("template[data-lazy]")];
    }

    /** Marks stale the tabs waiting: an event was applied by hand, without them. */
    function touch() {
        for (const template of pending()) {
            if (template.parentElement?.matches("mdui-tab-panel")) stale.add(template);
        }
    }

    /** Shows `template`'s content in its place; returns its parent, null when it was shown already. */
    function show(template, { quiet = false } = {}) {
        if (!template?.isConnected) return null;
        const root = template.parentElement;
        const wasStale = stale.has(template);
        template.replaceWith(template.content);
        if (!quiet) root.dispatchEvent(new CustomEvent("catapult:lazy", { bubbles: true, detail: { root, stale: wasStale } }));
        return root;
    }

    /** Shows the template right under `parent`, if it still has one. */
    function showIn(parent, options) {
        return show(parent?.querySelector(":scope > template[data-lazy]"), options);
    }

    // Captured on its way down: built before the element's own listeners run, and mdui measures the body (or places
    // the menu) right after.
    document.addEventListener("open", event => {
        if (event.target instanceof Element && event.target.matches("mdui-collapse-item, mdui-select")) showIn(event.target);
    }, true);

    return { show, showIn, touch, pending };
})();
