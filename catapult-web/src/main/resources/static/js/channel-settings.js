// These are mdui-switch elements, not native checkboxes: the :checked pseudo-class
// never matches them, so the checked state has to be read off the property.
function checkedValues(root, selector) {
    return Array.from(root.querySelectorAll(selector)).filter(sw => sw.checked).map(sw => sw.value);
}

document.addEventListener("catapult:render", function () {
    const baseUrl = CatapultChannel.baseUrl();

    const cclForm = document.getElementById("channel-ccl-settings-form");
    if (cclForm) {
        document.getElementById("ccl-settings-save-btn").addEventListener("click", async () => {
            const enabled = document.getElementById("ccl-enabled-checkbox").checked;
            const blockedCcls = checkedValues(cclForm, ".ccl-block-checkbox");
            await CatapultChannel.postJson(`${baseUrl}/settings/ccl`, { cclEnabled: enabled, blockedCcls });
            await CatapultChannel.refresh();
        });
    }

    const twForm = document.getElementById("channel-tw-settings-form");
    if (twForm) {
        document.getElementById("tw-settings-save-btn").addEventListener("click", async () => {
            const enabled = document.getElementById("tw-enabled-checkbox").checked;
            const blockedTws = checkedValues(twForm, ".tw-block-checkbox");
            await CatapultChannel.postJson(`${baseUrl}/settings/tws`, { enabled, blockedTws });
            await CatapultChannel.refresh();
        });
    }

    const noGameForm = document.getElementById("channel-no-game-settings-form");
    if (noGameForm) {
        const gameInput = document.getElementById("no-game-game-input");
        const gameIdInput = document.getElementById("no-game-game-id");
        GameSearch.attach(gameInput, document.getElementById("no-game-game-results"),
                `${baseUrl}/games/search`, game => { gameIdInput.value = game.id; });

        document.getElementById("no-game-settings-save-btn").addEventListener("click", async () => {
            const ccls = checkedValues(noGameForm, ".no-game-ccl-checkbox");
            await CatapultChannel.postJson(`${baseUrl}/settings/no-game`, {
                twitchGameId: gameIdInput.value || null,
                twitchGameName: gameInput.value || null,
                ccls,
                applyOnStreamStart: document.getElementById("no-game-apply-start").checked,
                applyOnNoGame: document.getElementById("no-game-apply-no-game").checked,
                applyOnStreamEnd: document.getElementById("no-game-apply-end").checked
            });
            await CatapultChannel.refresh();
        });
    }

    const incompleteFallbackForm = document.getElementById("channel-incomplete-fallback-settings-form");
    if (incompleteFallbackForm) {
        const gameInput = document.getElementById("incomplete-fallback-game-input");
        const gameIdInput = document.getElementById("incomplete-fallback-game-id");
        GameSearch.attach(gameInput, document.getElementById("incomplete-fallback-game-results"),
                `${baseUrl}/games/search`, game => { gameIdInput.value = game.id; });

        document.getElementById("incomplete-fallback-settings-save-btn").addEventListener("click", async () => {
            const ccls = checkedValues(incompleteFallbackForm, ".incomplete-fallback-ccl-checkbox");
            await CatapultChannel.postJson(`${baseUrl}/settings/incomplete-fallback`, {
                twitchGameId: gameIdInput.value || null,
                twitchGameName: gameInput.value || null,
                ccls
            });
            await CatapultChannel.refresh();
        });
    }
});
