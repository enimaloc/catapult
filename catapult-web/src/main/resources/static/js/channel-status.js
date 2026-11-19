document.addEventListener("catapult:render", function () {
    CatapultChannel.on("channel-bot-toggle", "change", event =>
        CatapultChannel.postSwitch(event.currentTarget, event.currentTarget.dataset.toggleUrl, {}));

    // The game now detected comes back as a GameChangedEvent (see ChannelActionsController).
    CatapultChannel.on("channel-recheck-game", "click", event =>
        CatapultChannel.postJson(event.currentTarget.dataset.recheckUrl, {}));

    // The channel's own emergency stops are lifted by catapult-api; the page is re-rendered to show it.
    document.querySelectorAll(".channel-emergency-resume").forEach(button => {
        // The event fires on each render: a button that survived one must not get a second listener
        if (button.dataset.bound) return;
        button.dataset.bound = "true";
        button.addEventListener("click", async () => {
            const response = await CatapultChannel.postJson(button.dataset.resumeUrl, {});
            if (response.ok) CatapultChannel.refresh();
        });
    });
});
