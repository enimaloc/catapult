(function () {
    const botToggle = document.getElementById("channel-bot-toggle");
    if (botToggle) {
        botToggle.addEventListener("change", async () => {
            await CatapultCsrf.postJson(botToggle.dataset.toggleUrl, {});
            navigate(location.pathname.replace(/^\/+/, ""), false);
        });
    }

    const recheckButton = document.getElementById("channel-recheck-game");
    if (recheckButton) {
        recheckButton.addEventListener("click", async () => {
            await CatapultCsrf.postJson(recheckButton.dataset.recheckUrl, {});
            navigate(location.pathname.replace(/^\/+/, ""), false);
        });
    }
})();
