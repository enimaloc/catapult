document.addEventListener("catapult:render", function () {
    const card = document.getElementById("channel-dtdd-mapping-card");
    if (!card) return;

    // ApiChannelDtddMappingController is globally scoped, not username-scoped, so these
    // routes don't carry the username (unlike CatapultChannel.baseUrl()).
    const dtddBaseUrl = "/channel/dtdd-mapping";
    const igdbId = card.dataset.igdbId;

    const validateBtn = document.getElementById("dtdd-validate-btn");
    if (validateBtn) {
        validateBtn.addEventListener("click", async () => {
            await CatapultChannel.postJson(`${dtddBaseUrl}/validate`, { igdbId });
            await CatapultChannel.refresh();
        });
    }

    const correctBtn = document.getElementById("dtdd-correct-btn");
    const dialog = document.getElementById("dtdd-search-dialog");
    correctBtn.addEventListener("click", () => { dialog.hidden = !dialog.hidden; });

    const searchInput = document.getElementById("dtdd-search-input");
    const results = document.getElementById("dtdd-search-results");
    let debounceTimer;
    searchInput.addEventListener("input", () => {
        clearTimeout(debounceTimer);
        const q = searchInput.value.trim();
        if (q.length < 2) {
            results.innerHTML = "";
            return;
        }
        debounceTimer = setTimeout(async () => {
            const response = await fetch(`${dtddBaseUrl}/search?q=${encodeURIComponent(q)}`);
            const data = await response.json();
            results.innerHTML = "";
            data.results.forEach(game => {
                const item = document.createElement("mdui-list-item");
                item.textContent = game.name;
                item.addEventListener("click", async () => {
                    await CatapultChannel.postJson(`${dtddBaseUrl}/propose`,
                            { igdbId, dtddId: game.dtddId, reason: "correction" });
                    await CatapultChannel.refresh();
                });
                results.appendChild(item);
            });
        }, 300);
    });
});
