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

    list.querySelectorAll(".binding-edit-btn").forEach(btn => {
        const row = btn.closest("[data-binding-id]");
        const panel = row.querySelector(".binding-edit-panel");
        btn.addEventListener("click", () => { panel.hidden = !panel.hidden; });

        const gameInput = panel.querySelector(".binding-edit-game-input");
        const gameIdInput = panel.querySelector(".binding-edit-game-id");
        const results = panel.querySelector(".binding-edit-game-results");
        GameSearch.attach(gameInput, results, `${CatapultChannel.baseUrl()}/games/search`, game => {
            gameIdInput.value = game.id;
        });

        panel.querySelector(".binding-edit-save-btn").addEventListener("click", async () => {
            const ccls = Array.from(panel.querySelectorAll(".binding-edit-ccl-checkbox:checked"))
                .map(cb => cb.value);
            await CatapultChannel.postJson(bindingUrl(row, ""), {
                twitchGameId: gameIdInput.value || null,
                twitchGameName: gameInput.value || null,
                ccls
            });
        });
    });

    list.querySelectorAll(".binding-tw-btn").forEach(btn => {
        const row = btn.closest("[data-binding-id]");
        const panel = row.querySelector(".binding-tw-panel");
        btn.addEventListener("click", () => { panel.hidden = !panel.hidden; });

        panel.querySelector(".binding-tw-save-btn").addEventListener("click", async () => {
            const enabled = panel.querySelector(".binding-tw-enabled-checkbox").checked;
            const tws = Array.from(panel.querySelectorAll(".binding-tw-checkbox:checked")).map(cb => cb.value);
            await CatapultChannel.postJson(twUrl(row, "tw-enabled"), { enabled });
            await CatapultChannel.postJson(twUrl(row, "tws"), { tws });
        });

        panel.querySelector(".binding-tw-reset-btn").addEventListener("click", async () => {
            await CatapultChannel.postJson(twUrl(row, "tws/reset"), {});
        });
    });
});
