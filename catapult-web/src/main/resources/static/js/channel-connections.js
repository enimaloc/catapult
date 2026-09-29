/**
 * Handlers for the Steam/Minecraft connection cards. Split into small attach*
 * functions (rather than one big listener block) because the SSE-driven event
 * handlers in channel-page.js rebuild the Steam token body and the Minecraft
 * status body from scratch when their state changes server-side, and need to
 * re-attach listeners to the fresh elements afterwards.
 */
window.CatapultConnections = (function () {
    function attachSteamHandlers() {
        const baseUrl = CatapultChannel.baseUrl();

        const shareToggle = document.getElementById("steam-token-shared");
        if (shareToggle) {
            shareToggle.addEventListener("change", async () => {
                await CatapultChannel.postJson(`${baseUrl}/settings/steam-personal-token/sharing`,
                    { shared: shareToggle.checked });
            });
        }

        const deleteBtn = document.getElementById("steam-token-delete-btn");
        if (deleteBtn) {
            deleteBtn.addEventListener("click", async () => {
                await CatapultChannel.postJson(`${baseUrl}/settings/steam-personal-token/delete`, {});
            });
        }

        const saveBtn = document.getElementById("steam-token-save-btn");
        if (saveBtn) {
            saveBtn.addEventListener("click", async () => {
                const token = document.getElementById("steam-token-input").value;
                const shared = document.getElementById("steam-token-share-new").checked;
                await CatapultChannel.postJson(`${baseUrl}/settings/steam-personal-token`, { token, shared });
            });
        }

        const refreshCacheBtn = document.getElementById("steam-refresh-cache-btn");
        if (refreshCacheBtn) {
            refreshCacheBtn.addEventListener("click", async () => {
                await CatapultChannel.postJson(`${baseUrl}/steam/refresh-profile-cache`, {});
                await CatapultChannel.refresh();
            });
        }
    }

    function attachMinecraftHandlers() {
        const baseUrl = CatapultChannel.baseUrl();

        const enrollBtn = document.getElementById("minecraft-enroll-btn");
        if (enrollBtn) {
            enrollBtn.addEventListener("click", async () => {
                const name = document.getElementById("minecraft-name-input").value;
                await CatapultChannel.postJson(`${baseUrl}/minecraft/enroll`, { name });
            });
        }

        const checkBtn = document.getElementById("minecraft-check-btn");
        if (checkBtn) {
            checkBtn.addEventListener("click", async () => {
                await CatapultChannel.postJson(`${baseUrl}/minecraft/sync`, {});
            });
        }

        const disconnectBtn = document.getElementById("minecraft-disconnect-btn");
        if (disconnectBtn) {
            disconnectBtn.addEventListener("click", async () => {
                await CatapultChannel.postJson(`${baseUrl}/minecraft/disconnect`, {});
            });
        }
    }

    document.addEventListener("catapult:render", function () {
        if (!document.getElementById("channel-connections")) return;
        attachSteamHandlers();
        attachMinecraftHandlers();
    });

    return { attachSteamHandlers, attachMinecraftHandlers };
})();
