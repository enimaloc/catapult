/**
 * Settings tab. Each card's fields already show what it saves, so a save only posts: no
 * re-render, which would lose the scroll position and any card being edited.
 */
document.addEventListener("catapult:render", function () {
    if (!document.getElementById("channel-ccl-settings-form")) return;

    const { on, checkedValues, postJson } = CatapultChannel;
    const baseUrl = CatapultChannel.baseUrl();
    const byId = id => document.getElementById(id);

    on("ccl-settings-save-btn", "click", () => postJson(`${baseUrl}/settings/ccl`, {
        cclEnabled: byId("ccl-enabled-checkbox").checked,
        blockedCcls: checkedValues(byId("channel-ccl-settings-form"), ".ccl-block-checkbox")
    }));

    on("tw-settings-save-btn", "click", () => postJson(`${baseUrl}/settings/tws`, {
        enabled: byId("tw-enabled-checkbox").checked,
        blockedTws: checkedValues(byId("channel-tw-settings-form"), ".tw-block-checkbox")
    }));

    // The "no game" and "incomplete game" cards share the same game picker + ccl
    // switches shape; only their id prefix, endpoint and extra fields differ.
    function attachDefaultGameCard(prefix, endpoint, extraFields = () => ({})) {
        const gameInput = byId(`${prefix}-game-input`);
        const gameIdInput = byId(`${prefix}-game-id`);
        GameSearch.attach(gameInput, byId(`${prefix}-game-results`), `${baseUrl}/games/search`,
            game => { gameIdInput.value = game.id; });

        on(`${prefix}-settings-save-btn`, "click", () => postJson(`${baseUrl}/settings/${endpoint}`, {
            twitchGameId: gameIdInput.value || null,
            twitchGameName: gameInput.value || null,
            ccls: checkedValues(byId(`channel-${prefix}-settings-form`), `.${prefix}-ccl-checkbox`),
            ...extraFields()
        }));
    }

    attachDefaultGameCard("no-game", "no-game", () => ({
        applyOnStreamStart: byId("no-game-apply-start").checked,
        applyOnNoGame: byId("no-game-apply-no-game").checked,
        applyOnStreamEnd: byId("no-game-apply-end").checked
    }));
    attachDefaultGameCard("incomplete-fallback", "incomplete-fallback");
});
