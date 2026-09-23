(function () {
    const list = document.getElementById("channel-bindings-list");
    if (!list) return;

    function bindingUrl(row, suffix) {
        const username = location.pathname.split("/")[2];
        const base = `/channel/${username}/bindings/${row.dataset.bindingId}`;
        return suffix ? `${base}/${suffix}` : base;
    }

    async function refresh() {
        navigate(location.pathname.replace(/^\/+/, ""), false);
    }

    list.querySelectorAll(".binding-ccl-toggle").forEach(toggle => {
        toggle.addEventListener("change", async () => {
            const row = toggle.closest("[data-binding-id]");
            await CatapultCsrf.postJson(bindingUrl(row, "ccl-toggle"), { enabled: toggle.checked });
            await refresh();
        });
    });

    list.querySelectorAll(".binding-ignored-toggle").forEach(toggle => {
        toggle.addEventListener("change", async () => {
            const row = toggle.closest("[data-binding-id]");
            await CatapultCsrf.postJson(bindingUrl(row, "ignored-toggle"), { ignored: toggle.checked });
            await refresh();
        });
    });

    list.querySelectorAll(".binding-delete-btn").forEach(btn => {
        btn.addEventListener("click", async () => {
            const row = btn.closest("[data-binding-id]");
            await CatapultCsrf.postJson(bindingUrl(row, "delete"), {});
            await refresh();
        });
    });

    list.querySelectorAll(".binding-edit-btn").forEach(btn => {
        const row = btn.closest("[data-binding-id]");
        const panel = row.querySelector(".binding-edit-panel");
        btn.addEventListener("click", () => { panel.hidden = !panel.hidden; });

        const gameInput = panel.querySelector(".binding-edit-game-input");
        const gameIdInput = panel.querySelector(".binding-edit-game-id");
        const results = panel.querySelector(".binding-edit-game-results");
        const username = location.pathname.split("/")[2];
        GameSearch.attach(gameInput, results, `/channel/${username}/games/search`, game => {
            gameIdInput.value = game.id;
        });

        panel.querySelector(".binding-edit-save-btn").addEventListener("click", async () => {
            const ccls = Array.from(panel.querySelectorAll(".binding-edit-ccl-checkbox:checked"))
                .map(cb => cb.value);
            await CatapultCsrf.postJson(bindingUrl(row, ""), {
                twitchGameId: gameIdInput.value || null,
                twitchGameName: gameInput.value || null,
                ccls
            });
            await refresh();
        });
    });

    list.querySelectorAll(".binding-tw-btn").forEach(btn => {
        const row = btn.closest("[data-binding-id]");
        const panel = row.querySelector(".binding-tw-panel");
        btn.addEventListener("click", () => { panel.hidden = !panel.hidden; });

        panel.querySelector(".binding-tw-save-btn").addEventListener("click", async () => {
            const enabled = panel.querySelector(".binding-tw-enabled-checkbox").checked;
            const tws = Array.from(panel.querySelectorAll(".binding-tw-checkbox:checked")).map(cb => cb.value);
            await CatapultCsrf.postJson(bindingUrl(row, "tw-enabled"), { enabled });
            await CatapultCsrf.postJson(bindingUrl(row, "tws"), { tws });
            await refresh();
        });

        const resetBtn = panel.querySelector(".binding-tw-reset-btn");
        if (resetBtn) {
            resetBtn.addEventListener("click", async () => {
                await CatapultCsrf.postJson(bindingUrl(row, "tws/reset"), {});
                await refresh();
            });
        }
    });
})();
