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
    // squaring the joined header/body corners works either way.
    list.querySelectorAll("mdui-collapse-item").forEach(item => {
        item.addEventListener("open", () => item.classList.add("is-open"));
        item.addEventListener("close", () => item.classList.remove("is-open"));
    });

    // Generic actions: data-post names the endpoint suffix, data-key the boolean field.
    list.querySelectorAll("mdui-switch[data-post]").forEach(el => {
        el.addEventListener("change", () =>
            postSwitch(el, bindingUrl(rowOf(el), el.dataset.post), { [el.dataset.key]: el.checked }));
    });

    list.querySelectorAll("mdui-button-icon[data-post]").forEach(el => {
        el.addEventListener("click", () => postJson(bindingUrl(rowOf(el), el.dataset.post), {}));
    });

    // updateBinding replaces twitchGameId/twitchGameName/ccls wholesale, so every save —
    // whether triggered by picking a game or ticking a ccl — must resend all three.
    list.querySelectorAll(".binding-edit-panel").forEach(panel => {
        const row = rowOf(panel);
        const gameInput = panel.querySelector(".binding-edit-game-input");
        const gameIdInput = panel.querySelector(".binding-edit-game-id");
        // The ccl switches live in the sibling .binding-ccl-panel, within the same row.
        const cclRoot = panel.parentElement;

        // A ccl switch's save puts that switch back when it doesn't go through.
        function saveBinding(gameId, gameName, sw) {
            const body = {
                twitchGameId: gameId || null,
                twitchGameName: gameName || null,
                ccls: checkedValues(cclRoot, ".binding-edit-ccl-checkbox")
            };
            return sw ? postSwitch(sw, bindingUrl(row, ""), body) : postJson(bindingUrl(row, ""), body);
        }

        GameSearch.attach(gameInput, panel.querySelector(".binding-edit-game-results"),
            `${CatapultChannel.baseUrl()}/games/search`, game => {
                gameIdInput.value = game.id;
                saveBinding(game.id, game.name);
            });

        cclRoot.querySelectorAll(".binding-edit-ccl-checkbox").forEach(sw => {
            sw.addEventListener("change", () => saveBinding(gameIdInput.value, gameInput.value, sw));
        });
    });

    list.querySelectorAll(".binding-tw-panel").forEach(panel => {
        const row = rowOf(panel);

        panel.querySelector(".binding-tw-enabled-checkbox").addEventListener("change", event =>
            postSwitch(event.currentTarget, twUrl(row, "tw-enabled"), { enabled: event.currentTarget.checked }));

        panel.querySelectorAll(".binding-tw-checkbox").forEach(sw => {
            sw.addEventListener("change", () =>
                postSwitch(sw, twUrl(row, "tws"), { tws: checkedValues(panel, ".binding-tw-checkbox") }));
        });

        panel.querySelector(".binding-tw-reset-btn").addEventListener("click", () =>
            postJson(twUrl(row, "tws/reset"), {}));
    });
});
