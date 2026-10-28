document.addEventListener("catapult:render", function () {
    const list = document.getElementById("channel-bindings-list");
    if (!list) return;

    const { postJson, postSwitch, checkedValues } = CatapultChannel;
    const rowOf = el => el.closest("[data-binding-id]");

    // ccl-toggle/ignored-toggle/delete/edit hit ApiChannelActionsController, which IS
    // username-scoped.
    function bindingUrl(row, suffix) {
        const base = `${CatapultChannel.baseUrl()}/bindings/${row.dataset.bindingId}`;
        return suffix ? `${base}/${suffix}` : base;
    }

    // tws/tws-reset/tw-enabled hit ApiChannelTwController, which is binding-scoped, not
    // username-scoped, so these routes don't carry the username.
    function twUrl(row, suffix) {
        return `/channel/bindings/${row.dataset.bindingId}/${suffix}`;
    }

    // mdui-collapse fires open/close but doesn't reflect them on the item: mirror it as
    // .is-open, which the server also sets on the current game's row, so the CSS
    // squaring the joined header/body corners works either way. (mdui's open/close
    // events bubble: a select's inside the row is not the row's.)
    // A row's body is built only once it is opened (lazy.js): with the page for the row
    // open with it (the current game's), else by lazy.js on its first opening, and bound
    // then; one the page holds already at once.
    list.querySelectorAll("mdui-collapse-item").forEach(item => {
        item.addEventListener("open", event => {
            if (event.target === item) item.classList.add("is-open");
        });
        item.addEventListener("catapult:lazy", event => {
            if (event.detail.root === item) bindRow(item);
        });
        item.addEventListener("close", event => {
            if (event.target === item) item.classList.remove("is-open");
        });
        const waiting = item.querySelector(":scope > template[data-lazy]");
        if (!waiting) bindRow(item);
        else if (item.classList.contains("is-open") && CatapultLazy.show(waiting, { quiet: true })) bindRow(item);
    });

    /** Shows `boxArtUrl` (the game picked's) in the row's header, an empty frame when it has none to show. */
    function showBoxArt(row, boxArtUrl) {
        const current = row.querySelector(".binding-box-art");
        if (!current) return;
        const src = GameSearch.boxArt(boxArtUrl);
        const art = document.createElement(src ? "img" : "span");
        art.slot = "icon";
        art.className = "box-art binding-box-art";
        if (src) {
            art.src = src;
            art.alt = "";
        }
        current.replaceWith(art);
    }

    /** Binds the controls of a row's body, once built. */
    function bindRow(row) {
        // Generic actions: data-post names the endpoint suffix, data-key the boolean field.
        row.querySelectorAll("mdui-switch[data-post]").forEach(el => {
            el.addEventListener("change", () =>
                postSwitch(el, bindingUrl(rowOf(el), el.dataset.post), { [el.dataset.key]: el.checked }));
        });

        row.querySelectorAll("mdui-button-icon[data-post]").forEach(el => {
            el.addEventListener("click", () => postJson(bindingUrl(rowOf(el), el.dataset.post), {}));
        });

        // updateBinding replaces twitchGameId/twitchGameName/ccls wholesale, so every save —
        // whether triggered by picking a game or ticking a ccl — must resend all three.
        row.querySelectorAll(".binding-edit-panel").forEach(panel => {
            const gameInput = panel.querySelector(".binding-edit-game-input");
            const gameIdInput = panel.querySelector(".binding-edit-game-id");
            // The ccl switches live in the sibling .binding-ccl-panel, within the same row.
            const cclRoot = panel.parentElement;

            // Only a picked game is sent (with its own id), never the text being typed; an emptied
            // field sends neither. A ccl switch's save puts that switch back when it doesn't go through.
            const game = GameSearch.attach(gameInput, panel.querySelector(".binding-edit-game-results"),
                `${CatapultChannel.baseUrl()}/games/search`, picked => {
                    gameIdInput.value = picked.id;
                    showBoxArt(row, picked.boxArtUrl);
                    saveBinding();
                }, { initial: { id: gameIdInput.value, name: gameInput.value } });

            function saveBinding(sw) {
                const selected = game.selected();
                const body = {
                    twitchGameId: selected?.id ?? null,
                    twitchGameName: selected?.name ?? null,
                    ccls: checkedValues(cclRoot, ".binding-edit-ccl-checkbox")
                };
                return sw ? postSwitch(sw, bindingUrl(row, ""), body) : postJson(bindingUrl(row, ""), body);
            }

            cclRoot.querySelectorAll(".binding-edit-ccl-checkbox").forEach(sw => {
                sw.addEventListener("change", () => saveBinding(sw));
            });
        });

        row.querySelectorAll(".binding-tw-panel").forEach(panel => {

            panel.querySelector(".binding-tw-enabled-checkbox").addEventListener("change", event =>
                postSwitch(event.currentTarget, twUrl(row, "tw-enabled"), { enabled: event.currentTarget.checked }));

            panel.querySelectorAll(".binding-tw-checkbox").forEach(sw => {
                sw.addEventListener("change", () =>
                    postSwitch(sw, twUrl(row, "tws"), { tws: checkedValues(panel, ".binding-tw-checkbox") }));
            });

            panel.querySelector(".binding-tw-reset-btn").addEventListener("click", () =>
                postJson(twUrl(row, "tws/reset"), {}));
        });
    }
});
