(function () {
    const steamCard = document.querySelector('[data-conn="steam"]');
    if (!steamCard) return;

    const username = location.pathname.split("/")[2];
    const baseUrl = `/channel/${username}`;

    async function refresh() {
        navigate(location.pathname.replace(/^\/+/, ""), false);
    }

    const shareToggle = document.getElementById("steam-token-shared");
    if (shareToggle) {
        shareToggle.addEventListener("change", async () => {
            await CatapultCsrf.postJson(`${baseUrl}/settings/steam-personal-token/sharing`,
                { shared: shareToggle.checked });
            await refresh();
        });
    }

    const deleteBtn = document.getElementById("steam-token-delete-btn");
    if (deleteBtn) {
        deleteBtn.addEventListener("click", async () => {
            await CatapultCsrf.postJson(`${baseUrl}/settings/steam-personal-token/delete`, {});
            await refresh();
        });
    }

    const saveBtn = document.getElementById("steam-token-save-btn");
    if (saveBtn) {
        saveBtn.addEventListener("click", async () => {
            const token = document.getElementById("steam-token-input").value;
            const shared = document.getElementById("steam-token-share-new").checked;
            await CatapultCsrf.postJson(`${baseUrl}/settings/steam-personal-token`, { token, shared });
            await refresh();
        });
    }

    const refreshCacheBtn = document.getElementById("steam-refresh-cache-btn");
    if (refreshCacheBtn) {
        refreshCacheBtn.addEventListener("click", async () => {
            await CatapultCsrf.postJson(`${baseUrl}/steam/refresh-profile-cache`, {});
            await refresh();
        });
    }
})();
