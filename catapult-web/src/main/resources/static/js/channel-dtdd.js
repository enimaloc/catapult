document.addEventListener("catapult:render", function () {
    const card = document.getElementById("channel-dtdd-mapping-card");
    if (!card) return;

    const { on, postAndRefresh } = CatapultChannel;
    // ApiChannelDtddMappingController is globally scoped, not username-scoped, so these
    // routes don't carry the username (unlike CatapultChannel.baseUrl()).
    const dtddBaseUrl = "/channel/dtdd-mapping";
    const igdbId = card.dataset.igdbId;

    on("dtdd-validate-btn", "click", () => postAndRefresh(`${dtddBaseUrl}/validate`, { igdbId }));

    const dialog = document.getElementById("dtdd-search-dialog");
    on("dtdd-correct-btn", "click", () => { dialog.hidden = !dialog.hidden; });

    GameSearch.attach(document.getElementById("dtdd-search-input"), document.getElementById("dtdd-search-results"),
        `${dtddBaseUrl}/search`,
        game => postAndRefresh(`${dtddBaseUrl}/propose`, { igdbId, dtddId: game.dtddId, reason: "correction" }),
        { pick: data => data.results });
});
