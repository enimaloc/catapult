document.addEventListener("catapult:render", function () {
    const list = document.getElementById("channel-bindings-list");
    if (!list) return;

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

    list.querySelectorAll("mdui-switch[data-post]").forEach(el => {
        el.addEventListener("change", async () => {
            const row = el.closest("[data-binding-id]");
            let body = {};
            body[el.dataset.key] = el.checked;
            await CatapultChannel.postJson(bindingUrl(row, el.dataset.post), body);
        })
    })

    list.querySelectorAll("mdui-button-icon[data-post]").forEach(el => {
        el.addEventListener("click", async () => {
            const row = el.closest("[data-binding-id]");
            await CatapultChannel.postJson(bindingUrl(row, el.dataset.post), {});
        })
    })

    // updateBinding replaces twitchGameId/twitchGameName/ccls wholesale, so every save —
    // whether triggered by picking a game or ticking a ccl — must resend all three.
    list.querySelectorAll(".binding-edit-panel").forEach(panel => {
        const row = panel.closest("[data-binding-id]");
        const gameInput = panel.querySelector(".binding-edit-game-input");
        const gameIdInput = panel.querySelector(".binding-edit-game-id");
        const results = panel.querySelector(".binding-edit-game-results");
        // The ccl switches live in the sibling .binding-ccl-panel, within the same row.
        const cclRoot = panel.parentElement;

        function saveBinding(gameId, gameName) {
            const ccls = Array.from(cclRoot.querySelectorAll(".binding-edit-ccl-checkbox"))
                .filter(sw => sw.checked)
                .map(sw => sw.value);
            return CatapultChannel.postJson(bindingUrl(row, ""), {
                twitchGameId: gameId || null,
                twitchGameName: gameName || null,
                ccls
            });
        }

        GameSearch.attach(gameInput, results, `${CatapultChannel.baseUrl()}/games/search`, game => {
            gameIdInput.value = game.id;
            saveBinding(game.id, game.name);
        });

        cclRoot.querySelectorAll(".binding-edit-ccl-checkbox").forEach(sw => {
            sw.addEventListener("change", () => {
                saveBinding(gameIdInput.value, gameInput.value);
            });
        });
    });

    list.querySelectorAll(".binding-tw-panel").forEach(panel => {
        const row = panel.closest("[data-binding-id]");

        panel.querySelector(".binding-tw-enabled-checkbox").addEventListener("change", async (event) => {
            await CatapultChannel.postJson(twUrl(row, "tw-enabled"), { enabled: event.target.checked });
        });

        panel.querySelectorAll(".binding-tw-checkbox").forEach(sw => {
            sw.addEventListener("change", async () => {
                const tws = Array.from(panel.querySelectorAll(".binding-tw-checkbox"))
                    .filter(cb => cb.checked)
                    .map(cb => cb.value);
                await CatapultChannel.postJson(twUrl(row, "tws"), { tws });
            });
        });

        panel.querySelector(".binding-tw-reset-btn").addEventListener("click", async () => {
            await CatapultChannel.postJson(twUrl(row, "tws/reset"), {});
        });
    });
});
