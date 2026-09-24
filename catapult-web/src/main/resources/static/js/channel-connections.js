document.addEventListener("catapult:render", function () {
    if (!document.getElementById("channel-connections")) return;

    const baseUrl = CatapultChannel.baseUrl();

    const shareToggle = document.getElementById("steam-token-shared");
    if (shareToggle) {
        shareToggle.addEventListener("change", async () => {
            await CatapultChannel.postJson(`${baseUrl}/settings/steam-personal-token/sharing`,
                { shared: shareToggle.checked });
            await CatapultChannel.refresh();
        });
    }

    const deleteBtn = document.getElementById("steam-token-delete-btn");
    if (deleteBtn) {
        deleteBtn.addEventListener("click", async () => {
            await CatapultChannel.postJson(`${baseUrl}/settings/steam-personal-token/delete`, {});
            await CatapultChannel.refresh();
        });
    }

    const saveBtn = document.getElementById("steam-token-save-btn");
    if (saveBtn) {
        saveBtn.addEventListener("click", async () => {
            const token = document.getElementById("steam-token-input").value;
            const shared = document.getElementById("steam-token-share-new").checked;
            await CatapultChannel.postJson(`${baseUrl}/settings/steam-personal-token`, { token, shared });
            await CatapultChannel.refresh();
        });
    }

    const refreshCacheBtn = document.getElementById("steam-refresh-cache-btn");
    if (refreshCacheBtn) {
        refreshCacheBtn.addEventListener("click", async () => {
            await CatapultChannel.postJson(`${baseUrl}/steam/refresh-profile-cache`, {});
            await CatapultChannel.refresh();
        });
    }

    const enrollBtn = document.getElementById("minecraft-enroll-btn");
    if (enrollBtn) {
        enrollBtn.addEventListener("click", async () => {
            const name = document.getElementById("minecraft-name-input").value;
            await CatapultChannel.postJson(`${baseUrl}/minecraft/enroll`, { name });
            await CatapultChannel.refresh();
        });
    }

    const checkBtn = document.getElementById("minecraft-check-btn");
    if (checkBtn) {
        checkBtn.addEventListener("click", async () => {
            await CatapultChannel.postJson(`${baseUrl}/minecraft/sync`, {});
            await CatapultChannel.refresh();
        });
    }

    const disconnectBtn = document.getElementById("minecraft-disconnect-btn");
    if (disconnectBtn) {
        disconnectBtn.addEventListener("click", async () => {
            await CatapultChannel.postJson(`${baseUrl}/minecraft/disconnect`, {});
            await CatapultChannel.refresh();
        });
    }
});
