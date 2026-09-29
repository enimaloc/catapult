document.addEventListener("catapult:render", function () {
    const botToggle = document.getElementById("channel-bot-toggle");
    if (botToggle) {
        botToggle.addEventListener("change", async () => {
            await CatapultChannel.postJson(botToggle.dataset.toggleUrl, {});
        });
    }

    const recheckButton = document.getElementById("channel-recheck-game");
    if (recheckButton) {
        recheckButton.addEventListener("click", async () => {
            await CatapultChannel.postJson(recheckButton.dataset.recheckUrl, {});
            await CatapultChannel.refresh();
        });
    }
});
