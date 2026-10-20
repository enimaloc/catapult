/**
 * Handlers for the Steam/Minecraft/OBS connection cards. The Steam/Minecraft cards' elements are all
 * pre-rendered and only toggled via the `hidden` class (spa:if/spa:switch, see
 * channel.html) instead of being created/destroyed per SSE event, so handlers only
 * need attaching once, at render — no refresh() either: the SSE echo updates the cards.
 */
(function () {
    // Twitchat's chips: whether a Twitchat answers is only known here, by twitchat.js through OBS.
    function showTwitchatPresence() {
        const connected = window.CatapultTwitchat?.isTwitchatConnected() ?? false;
        document.querySelector(".twitchat-connected-chip")?.classList.toggle("hidden", !connected);
        document.querySelector(".twitchat-disconnected-chip")?.classList.toggle("hidden", connected);
    }

    document.addEventListener("catapult:twitchat:presence", showTwitchatPresence);

    // The OBS card's chips and buttons, as IntegrationCards renders them for these settings.
    function showObsEnabled(enabled) {
        const toggle = (selector, hidden) => document.querySelector(selector)?.classList.toggle("hidden", hidden);
        toggle(".obs-connected-chip", !enabled);
        toggle(".obs-not-connected-chip", enabled);
        toggle("#obs-connect-btn", enabled);
        toggle("#obs-disconnect-btn", !enabled);
    }

    document.addEventListener("catapult:render", function () {
        if (!document.getElementById("channel-connections")) return;
        showTwitchatPresence();

        const { on, postJson, postSwitch } = CatapultChannel;
        const baseUrl = CatapultChannel.baseUrl();
        const value = id => document.getElementById(id).value;
        const checked = id => document.getElementById(id).checked;

        // Steam. Linking goes through Steam's login in a popup, which closes itself once done
        // (the tab itself when the browser blocks popups); the card follows from the event.
        on("steam-connect-btn", "click", () => {
            const popup = window.open("/connect/steam", "catapult-steam", "popup,width=800,height=720");
            if (!popup) location.assign("/connect/steam");
        });
        on("steam-disconnect-btn", "click", () => postJson("/connect/steam/disconnect", {}));
        on("steam-token-shared", "change", event =>
            postSwitch(event.currentTarget, `${baseUrl}/settings/steam-personal-token/sharing`,
                { shared: event.currentTarget.checked }));
        on("steam-token-delete-btn", "click", () =>
            postJson(`${baseUrl}/settings/steam-personal-token/delete`, {}));
        on("steam-token-save-btn", "click", () =>
            postJson(`${baseUrl}/settings/steam-personal-token`,
                { token: value("steam-token-input"), shared: checked("steam-token-share-new") }));
        // Only drops the cached profile: nothing on the page shows it until the next check.
        on("steam-refresh-cache-btn", "click", () =>
            postJson(`${baseUrl}/steam/refresh-profile-cache`, {}));

        // Minecraft
        on("minecraft-enroll-btn", "click", () =>
            postJson(`${baseUrl}/minecraft/enroll`, { name: value("minecraft-name-input") }));
        on("minecraft-check-btn", "click", () => postJson(`${baseUrl}/minecraft/sync`, {}));
        on("minecraft-disconnect-btn", "click", () => postJson(`${baseUrl}/minecraft/disconnect`, {}));

        // OBS: connecting and disconnecting both save the whole form, only `enabled` differs.
        // An empty password field means "keep the stored one" (it's never sent back to the page),
        // and blank host/port fall back to catapult-api's defaults. No SSE event covers OBS, so the
        // chips and buttons flip here, and obs-session.js follows the new settings.
        const saveObs = async enabled => {
            const password = value("obs-password");
            const response = await postJson(`${baseUrl}/settings/obs`, {
                enabled,
                host: value("obs-host"),
                port: value("obs-port") === "" ? null : Number(value("obs-port")),
                password: password === "" ? null : password,
            });
            if (!response.ok) return;
            showObsEnabled(enabled);
            if (password !== "") {
                // Stored now: shown like any stored password, as a placeholder.
                const field = document.getElementById("obs-password");
                field.value = "";
                field.placeholder = "••••••••";
            }
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
})();
