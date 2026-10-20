/**
 * Settings tab. Each card's fields already show what it saves, so a save only posts: no
 * re-render, which would lose the scroll position and any card being edited.
 *
 * A save made elsewhere (another tab, a moderator) comes back as ChannelSettingsUpdatedEvent:
 * channel-events.js hands its settings to CatapultSettings.apply(), which sets every card's
 * fields to them.
 */
window.CatapultSettings = (function () {
    // The current render's game pickers, by card prefix.
    let games = {};

    const byId = id => document.getElementById(id);

    function setChecked(id, checked) {
        const sw = byId(id);
        if (sw) sw.checked = !!checked;
    }

    /** Checks the switches of class `switchClass` under the card `formId` whose value is in `values`. */
    function setGrid(formId, switchClass, values) {
        const wanted = new Set(values ?? []);
        byId(formId)?.querySelectorAll(`.${switchClass}`).forEach(sw => { sw.checked = wanted.has(sw.value); });
    }

    function setGame(prefix, id, name) {
        const game = id ? { id, name } : null;
        const idInput = byId(`${prefix}-game-id`);
        if (idInput) idInput.value = game?.id ?? "";
        if (games[prefix]) {
            games[prefix].select(game);
        } else {
            const input = byId(`${prefix}-game-input`);
            if (input) input.value = game?.name ?? "";
        }
    }

    /** Sets every settings card to `settings` (ChannelEvents.Settings: one entry per card). */
    function apply(settings) {
        if (!settings || !byId("channel-ccl-settings-form")) return;
        const { ccl, tw, noGame, incompleteFallback } = settings;
        if (ccl) {
            setChecked("ccl-enabled-checkbox", ccl.enabled);
            setGrid("channel-ccl-settings-form", "ccl-block-checkbox", ccl.blockedCcls);
        }
        if (tw) {
            setChecked("tw-enabled-checkbox", tw.enabled);
            setGrid("channel-tw-settings-form", "tw-block-checkbox", tw.blockedTws);
        }
        if (noGame) {
            setGame("no-game", noGame.twitchGameId, noGame.twitchGameName);
            setGrid("channel-no-game-settings-form", "no-game-ccl-checkbox", noGame.ccls);
            setChecked("no-game-apply-start", noGame.applyOnStreamStart);
            setChecked("no-game-apply-no-game", noGame.applyOnNoGame);
            setChecked("no-game-apply-end", noGame.applyOnStreamEnd);
        }
        if (incompleteFallback) {
            setGame("incomplete-fallback", incompleteFallback.twitchGameId, incompleteFallback.twitchGameName);
            setGrid("channel-incomplete-fallback-settings-form", "incomplete-fallback-ccl-checkbox",
                incompleteFallback.ccls);
        }
    }

    function track(prefix, game) {
        games[prefix] = game;
    }

    function reset() {
        games = {};
    }

    return { apply, track, reset };
})();

document.addEventListener("catapult:render", function () {
    CatapultSettings.reset();
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
    // switches shape; only their id prefix, endpoint and extra fields differ. Only a picked
    // game is saved (with its own id), never the text being typed; an emptied field saves neither.
    function attachDefaultGameCard(prefix, endpoint, extraFields = () => ({})) {
        const gameInput = byId(`${prefix}-game-input`);
        const gameIdInput = byId(`${prefix}-game-id`);
        const game = GameSearch.attach(gameInput, byId(`${prefix}-game-results`), `${baseUrl}/games/search`,
            picked => { gameIdInput.value = picked.id; },
            { initial: { id: gameIdInput.value, name: gameInput.value } });
        CatapultSettings.track(prefix, game);

        on(`${prefix}-settings-save-btn`, "click", () => postJson(`${baseUrl}/settings/${endpoint}`, {
            twitchGameId: game.selected()?.id ?? null,
            twitchGameName: game.selected()?.name ?? null,
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
