document.addEventListener("catapult:render", function () {
    CatapultChannel.on("channel-bot-toggle", "change", event =>
        CatapultChannel.postJson(event.currentTarget.dataset.toggleUrl, {}));

    // The game now detected comes back as a GameChangedEvent (see ChannelActionsController).
    CatapultChannel.on("channel-recheck-game", "click", event =>
        CatapultChannel.postJson(event.currentTarget.dataset.recheckUrl, {}));
});
