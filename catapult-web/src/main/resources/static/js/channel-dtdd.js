(function () {
    const card = document.getElementById("channel-dtdd-mapping-card");
    if (!card) return;

    const username = location.pathname.split("/")[2];
    const baseUrl = `/channel/${username}`;
    const igdbId = card.dataset.igdbId;

    async function refresh() {
        navigate(location.pathname.replace(/^\/+/, ""), false);
    }

    const validateBtn = document.getElementById("dtdd-validate-btn");
    if (validateBtn) {
        validateBtn.addEventListener("click", async () => {
            await CatapultCsrf.postJson(`${baseUrl}/dtdd-mapping/validate`, { igdbId });
            await refresh();
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
            const response = await fetch(`${baseUrl}/dtdd-mapping/search?q=${encodeURIComponent(q)}`);
            const data = await response.json();
            results.innerHTML = "";
            data.results.forEach(game => {
                const item = document.createElement("mdui-list-item");
                item.textContent = game.name;
                item.addEventListener("click", async () => {
                    await CatapultCsrf.postJson(`${baseUrl}/dtdd-mapping/propose`,
                            { igdbId, dtddId: game.dtddId, reason: "correction" });
                    await refresh();
                });
                results.appendChild(item);
            });
        }, 300);
    });
})();
