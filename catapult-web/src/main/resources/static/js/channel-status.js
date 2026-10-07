document.addEventListener("catapult:render", function () {
    CatapultChannel.on("channel-bot-toggle", "change", event =>
        CatapultChannel.postJson(event.currentTarget.dataset.toggleUrl, {}));

    CatapultChannel.on("channel-recheck-game", "click", event =>
        CatapultChannel.postAndRefresh(event.currentTarget.dataset.recheckUrl, {}));
});
