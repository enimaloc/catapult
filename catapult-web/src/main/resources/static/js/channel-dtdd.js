/**
 * The DTDD mapping card. Its outcomes are pre-rendered hidden (see overview.html), so a
 * validation or a proposal only reveals them instead of re-rendering the page.
 */
document.addEventListener("catapult:render", function () {
    const card = document.getElementById("channel-dtdd-mapping-card");
    if (!card) return;

    const { on, postJson } = CatapultChannel;
    // ApiChannelDtddMappingController is globally scoped, not username-scoped, so these
    // routes don't carry the username (unlike CatapultChannel.baseUrl()).
    const dtddBaseUrl = "/channel/dtdd-mapping";
    const igdbId = card.dataset.igdbId;
    const byId = id => document.getElementById(id);
    // Validating is only offered while no proposal is pending, nor the mapping verified.
    const hideValidate = () => byId("dtdd-validate-btn")?.classList.add("hidden");

    on("dtdd-validate-btn", "click", async () => {
        const response = await postJson(`${dtddBaseUrl}/validate`, { igdbId });
        if (!response.ok) return;
        hideValidate();
        byId("dtdd-verified")?.classList.remove("hidden");
        const confidence = byId("dtdd-confidence");
        if (confidence) confidence.textContent = "100%";
    });

    const dialog = byId("dtdd-search-dialog");
    on("dtdd-correct-btn", "click", () => { dialog.hidden = !dialog.hidden; });

    GameSearch.attach(byId("dtdd-search-input"), byId("dtdd-search-results"),
        `${dtddBaseUrl}/search`,
        async game => {
            const response = await postJson(`${dtddBaseUrl}/propose`, { igdbId, dtddId: game.dtddId, reason: "correction" });
            if (!response.ok) return;
            hideValidate();
            dialog.hidden = true;
            byId("dtdd-proposed")?.classList.remove("hidden");
        },
        { pick: data => data.results });
});
