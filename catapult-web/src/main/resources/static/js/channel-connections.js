/**
 * Handlers for the Steam/Minecraft/OBS connection cards. The Steam/Minecraft cards' elements are all
 * pre-rendered and only toggled via the `hidden` class (spa:if/spa:switch, see
 * channel.html) instead of being created/destroyed per SSE event, so handlers only
 * need attaching once, at render — no refresh() either: the SSE echo updates the cards.
 */
document.addEventListener("catapult:render", function () {
    if (!document.getElementById("channel-connections")) return;

    const { on, postJson, postAndRefresh } = CatapultChannel;
    const baseUrl = CatapultChannel.baseUrl();
    const value = id => document.getElementById(id).value;
    const checked = id => document.getElementById(id).checked;

    // Steam
    on("steam-token-shared", "change", event =>
        postJson(`${baseUrl}/settings/steam-personal-token/sharing`, { shared: event.currentTarget.checked }));
    on("steam-token-delete-btn", "click", () =>
        postJson(`${baseUrl}/settings/steam-personal-token/delete`, {}));
    on("steam-token-save-btn", "click", () =>
        postJson(`${baseUrl}/settings/steam-personal-token`,
            { token: value("steam-token-input"), shared: checked("steam-token-share-new") }));
    // No SSE event covers the profile cache yet.
    on("steam-refresh-cache-btn", "click", () =>
        postAndRefresh(`${baseUrl}/steam/refresh-profile-cache`, {}));

    // Minecraft
    on("minecraft-enroll-btn", "click", () =>
        postJson(`${baseUrl}/minecraft/enroll`, { name: value("minecraft-name-input") }));
    on("minecraft-check-btn", "click", () => postJson(`${baseUrl}/minecraft/sync`, {}));
    on("minecraft-disconnect-btn", "click", () => postJson(`${baseUrl}/minecraft/disconnect`, {}));

    // OBS: connecting and disconnecting both save the whole form, only `enabled` differs.
    // An empty password field means "keep the stored one" (it's never sent back to the page),
    // and blank host/port fall back to catapult-api's defaults. No SSE event covers OBS, hence
    // the refresh to flip the chips and buttons, and obs-session.js's to follow the new settings.
    const saveObs = async enabled => {
        await postAndRefresh(`${baseUrl}/settings/obs`, {
            enabled,
            host: value("obs-host"),
            port: value("obs-port") === "" ? null : Number(value("obs-port")),
            password: value("obs-password") === "" ? null : value("obs-password"),
        });
        await window.CatapultObsSession?.refresh();
    };
    on("obs-connect-btn", "click", () => saveObs(true));
    on("obs-disconnect-btn", "click", () => saveObs(false));

    // Twitchat: the branch rides along /me/obs, which obs-session.js hands to twitchat.js.
    on("twitchat-branch", "change", async event => {
        await postJson(`${baseUrl}/settings/twitchat/branch`, { branch: event.currentTarget.value });
        await window.CatapultObsSession?.refresh();
    });
});
