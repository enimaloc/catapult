/**
 * The Twitch extension layout of each configuration of the channel page (a binding's row, a custom binding's form,
 * the no-game and incomplete-game cards), drawn as the channel (fragments/extensions.html): the player with its
 * overlay slot and its two component slots, the three panel slots under it.
 *
 * - The broadcaster's installed extensions are read once per page (GET /channel/{u}/twitch/extensions), when its
 *   first layout is shown, and shared by all of them. Twitch listing none (or not read: no token, Twitch failing),
 *   each layout asks to reconnect to Twitch; a slot showing an extension no longer installed is struck through.
 * - A slot opens its menu: keep the current one, none, then the installed extensions of its type, those Twitch
 *   won't activate greyed out. A pick shows at once and posts the whole layout of its configuration to its
 *   data-save-url, kept slots left out; refused or not sent, the slot goes back as it was (CatapultChannel.postJson
 *   tells why in a snackbar).
 * - A layout without data-save-url (a custom binding not saved yet) stays disabled; enable() opens it once saved.
 *
 * A body built only once shown (lazy.js: a binding's row, the settings tab) is wired on its catapult:lazy.
 * Every text goes in through textContent or a property, never as markup.
 */
window.CatapultExtensions = (function () {
    // The page's installed extensions: the promise of their list (null: catapult-web couldn't be asked), and the
    // list once known (undefined till then).
    let installed = null;
    let known = undefined;
    const wired = new WeakSet();

    const layoutOf = el => el.closest(".binding-extensions-panel");

    /** The installed extensions, asked once per page. */
    function load() {
        if (!installed) {
            const asked = fetch(`${CatapultChannel.baseUrl()}/twitch/extensions`, { headers: { Accept: "application/json" } })
                .then(response => response.ok ? response.json() : null)
                .then(answer => Array.isArray(answer?.installed) ? answer.installed : null)
                .catch(() => null)
                // Another page rendered since: its own request answers it.
                .then(list => { if (installed === asked) known = list; return list; });
            installed = asked;
        }
        return installed;
    }

    /** What a slot shows: kept, emptied, or an extension (its id and name). */
    function stateOf(slot) {
        if (slot.classList.contains("is-keep")) return { kind: "keep" };
        if (slot.classList.contains("is-none")) return { kind: "none" };
        return { kind: "extension", id: slot.dataset.extensionId, name: slot.dataset.extensionName };
    }

    const same = (a, b) => a.kind === b.kind && (a.kind !== "extension" || a.id === b.id);

    /** Shows `state` in `slot`. */
    function show(slot, state) {
        const labels = layoutOf(slot).dataset;
        slot.classList.toggle("is-keep", state.kind === "keep");
        slot.classList.toggle("is-none", state.kind === "none");
        slot.classList.toggle("is-extension", state.kind === "extension");
        if (state.kind === "extension") {
            slot.dataset.extensionId = state.id;
            slot.dataset.extensionName = state.name ?? "";
        } else {
            delete slot.dataset.extensionId;
            delete slot.dataset.extensionName;
        }
        slot.querySelector(".extension-slot-value").textContent = state.kind === "keep" ? labels.labelKeep
            : state.kind === "none" ? labels.labelNone : (state.name || state.id);
        markMissing(slot);
    }

    /** Strikes `slot` through when it shows an extension Twitch lists no more (not when it lists none: unread). */
    function markMissing(slot) {
        const missing = Array.isArray(known) && known.length > 0 && slot.classList.contains("is-extension")
            && !known.some(extension => extension.id === slot.dataset.extensionId);
        slot.classList.toggle("is-missing", missing);
        if (missing) slot.title = layoutOf(slot).dataset.labelMissing;
        else slot.removeAttribute("title");
    }

    /** Brings `layout` to the installed extensions known: its reconnect line, its slots struck through. */
    function decorate(layout) {
        const none = Array.isArray(known) && known.length === 0;
        layout.querySelector(".extension-layout-empty")?.classList.toggle("hidden", !none);
        layout.querySelectorAll(".extension-slot").forEach(markMissing);
    }

    /** The layout `layout` shows, as its route takes it: every slot but the kept ones. */
    function bodyOf(layout) {
        return Array.from(layout.querySelectorAll(".extension-slot")).flatMap(slot => {
            const state = stateOf(slot);
            if (state.kind === "keep") return [];
            const extension = state.kind === "extension";
            return [{
                type: slot.dataset.type,
                slot: Number(slot.dataset.slot),
                extensionId: extension ? state.id : null,
                extensionName: extension ? (state.name || null) : null
            }];
        });
    }

    /** Shows `next` in `slot` and saves its layout; put back as it was when that doesn't go through. */
    async function pick(slot, next) {
        const layout = layoutOf(slot);
        const previous = stateOf(slot);
        if (!layout.dataset.saveUrl || layout.classList.contains("is-saving") || same(previous, next)) return;
        show(slot, next);
        layout.classList.add("is-saving");
        try {
            const response = await CatapultChannel.postJson(layout.dataset.saveUrl, bodyOf(layout));
            if (!response.ok) show(slot, previous);
        } finally {
            layout.classList.remove("is-saving");
        }
    }

    function menuItem(choice, text, { icon, endText, current, disabled } = {}) {
        const item = document.createElement("mdui-menu-item");
        item.dataset.choice = choice.kind;
        if (choice.kind === "extension") {
            item.dataset.extensionId = choice.id;
            item.dataset.extensionName = choice.name ?? "";
        }
        item.textContent = text;
        if (icon) item.icon = icon;
        if (endText) item.endText = endText;
        if (current) item.endIcon = "check";
        item.disabled = !!disabled;
        return item;
    }

    /** Fills the menu of `dropdown`'s slot: keep, none, then the installed extensions of its type. */
    function fillMenu(dropdown) {
        const slot = dropdown.querySelector(".extension-slot");
        const menu = dropdown.querySelector("mdui-menu");
        const labels = layoutOf(slot).dataset;
        const current = stateOf(slot);
        const items = [
            menuItem({ kind: "keep" }, labels.labelKeepCurrent, { icon: "restart_alt", current: current.kind === "keep" }),
            menuItem({ kind: "none" }, labels.labelNoExtension, { icon: "block", current: current.kind === "none" })
        ];
        if (known === undefined) {
            items.push(menuItem({ kind: "loading" }, labels.labelLoading, { icon: "hourglass_empty", disabled: true }));
            // Filled again once the list comes, if still open.
            load().then(() => { if (dropdown.open && dropdown.isConnected) fillMenu(dropdown); });
        }
        (known ?? []).filter(extension => extension.types?.includes(slot.dataset.type)).forEach(extension => {
            const choice = { kind: "extension", id: extension.id, name: extension.name };
            items.push(menuItem(choice, extension.name || extension.id, {
                icon: "extension",
                endText: extension.canActivate ? (extension.version ? `v${extension.version}` : "") : labels.labelUnavailable,
                current: same(current, choice),
                disabled: !extension.canActivate
            }));
        });
        menu.replaceChildren(...items);
    }

    function wireDropdown(dropdown) {
        if (wired.has(dropdown)) return;
        wired.add(dropdown);
        dropdown.addEventListener("open", event => { if (event.target === dropdown) fillMenu(dropdown); });
        // Its own opening and closing aren't the row's: they stop here, before the collapse item holding it.
        ["open", "opened", "close", "closed"].forEach(type => dropdown.addEventListener(type, event => {
            if (event.target === dropdown) event.stopPropagation();
        }));
        dropdown.querySelector("mdui-menu").addEventListener("click", event => {
            const item = event.target.closest("mdui-menu-item[data-choice]");
            if (!item || item.disabled || item.dataset.choice === "loading") return;
            const slot = dropdown.querySelector(".extension-slot");
            const { choice, extensionId, extensionName } = item.dataset;
            pick(slot, choice === "extension" ? { kind: "extension", id: extensionId, name: extensionName } : { kind: choice });
        });
    }

    /** Wires the layouts under `root` (itself included), and asks for the installed extensions if there are any. */
    function wire(root) {
        const layouts = Array.from(root.querySelectorAll(".binding-extensions-panel"));
        if (root.matches?.(".binding-extensions-panel")) layouts.push(root);
        if (!layouts.length) return;
        layouts.forEach(layout => layout.querySelectorAll(".extension-slot-dropdown").forEach(wireDropdown));
        if (known !== undefined) layouts.forEach(decorate);
        load().then(() => layouts.forEach(decorate));
    }

    /** Opens the layout under `root` to its binding saved now, at `saveUrl` (a custom binding's row added). */
    function enable(root, saveUrl) {
        const layout = root?.querySelector(".binding-extensions-panel");
        if (!layout) return;
        layout.dataset.saveUrl = saveUrl;
        layout.classList.remove("is-disabled");
        layout.querySelectorAll(".extension-slot-dropdown, .extension-slot").forEach(el => { el.disabled = false; });
        layout.querySelectorAll(".extension-layout-note:not(.extension-layout-empty)").forEach(note => note.remove());
        wire(layout);
    }

    document.addEventListener("catapult:render", () => {
        // A new page: its own installed extensions, asked when it shows a layout.
        installed = null;
        known = undefined;
        wire(document);
    });
    document.addEventListener("catapult:lazy", event => {
        if (event.detail?.root instanceof Element) wire(event.detail.root);
    });

    return { enable };
})();
